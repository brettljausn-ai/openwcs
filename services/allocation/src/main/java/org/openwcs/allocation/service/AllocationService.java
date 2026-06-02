package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.allocation.api.AllocateOrderRequest;
import org.openwcs.allocation.api.AllocationNotFoundException;
import org.openwcs.allocation.api.AllocationView;
import org.openwcs.allocation.client.InventoryClient;
import org.openwcs.allocation.client.MasterDataClient;
import org.openwcs.allocation.client.MasterDataClient.ShipperDef;
import org.openwcs.allocation.client.MasterDataClient.UomDef;
import org.openwcs.allocation.domain.AllocationLine;
import org.openwcs.allocation.domain.OrderAllocation;
import org.openwcs.allocation.domain.Pick;
import org.openwcs.allocation.domain.ShipperAssignment;
import org.openwcs.allocation.repo.OrderAllocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allocates outbound orders against pick-able locations and cubes them into shippers
 * (ADR 0002). On full reservation the order is FULFILLABLE with a pick + cube plan;
 * otherwise every reservation is released and the order is reported NOT_FULFILLABLE.
 */
@Service
public class AllocationService {

    private static final Logger log = LoggerFactory.getLogger(AllocationService.class);

    private final OrderAllocationRepository allocations;
    private final MasterDataClient masterData;
    private final InventoryClient inventory;

    public AllocationService(OrderAllocationRepository allocations,
                             MasterDataClient masterData,
                             InventoryClient inventory) {
        this.allocations = allocations;
        this.masterData = masterData;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public AllocationView get(String orderRef) {
        return AllocationView.from(allocations.findByOrderRef(orderRef)
                .orElseThrow(() -> new AllocationNotFoundException(orderRef)));
    }

    @Transactional
    public AllocationView allocate(AllocateOrderRequest request) {
        // Idempotent / retry: a prior FULFILLABLE plan is returned as-is; a prior
        // NOT_FULFILLABLE plan (no held reservations) is discarded and recomputed.
        var existing = allocations.findByOrderRef(request.orderRef());
        if (existing.isPresent()) {
            if ("FULFILLABLE".equals(existing.get().getStatus())) {
                return AllocationView.from(existing.get());
            }
            allocations.delete(existing.get());
            allocations.flush();
        }

        MasterDataClient.FulfillmentConfig config = masterData.fulfillmentConfig(request.warehouseId());
        String cubingMode = request.cubingMode() != null ? request.cubingMode() : config.cubingMode();
        List<UUID> pickLocations = masterData.pickLocationIds(request.warehouseId());

        OrderAllocation allocation = new OrderAllocation();
        allocation.setOrderRef(request.orderRef());
        allocation.setWarehouseId(request.warehouseId());
        allocation.setCubingMode(cubingMode);

        Map<UUID, List<UomDef>> uomsBySku = new HashMap<>();
        List<UUID> reservationsMade = new ArrayList<>();
        boolean fulfillable = true;

        try {
            for (AllocateOrderRequest.Line line : request.lines()) {
                List<UomDef> uoms = uomsBySku.computeIfAbsent(line.skuId(), masterData::skuUoms);
                int caseSize = PickBreakdown.caseSize(uoms);

                BigDecimal remaining = line.qty();
                List<Pick> picks = new ArrayList<>();
                for (UUID location : pickLocations) {
                    if (remaining.signum() <= 0) {
                        break;
                    }
                    BigDecimal atp = inventory.availableAtLocation(request.warehouseId(), line.skuId(), location);
                    BigDecimal take = remaining.min(atp);
                    if (take.signum() > 0) {
                        UUID reservationId =
                                inventory.reserve(request.warehouseId(), line.skuId(), take, location, request.orderRef());
                        reservationsMade.add(reservationId);
                        picks.add(new Pick(location, take, reservationId,
                                PickBreakdown.split(take.longValue(), config.allowedPickTypes(), caseSize)));
                        remaining = remaining.subtract(take);
                    }
                }

                AllocationLine allocationLine = new AllocationLine();
                allocationLine.setLineNo(line.lineNo());
                allocationLine.setSkuId(line.skuId());
                allocationLine.setRequestedQty(line.qty());
                allocationLine.setAllocatedQty(line.qty().subtract(remaining));
                allocationLine.setPicks(picks);
                if (remaining.signum() > 0) {
                    allocationLine.setStatus("SHORT");
                    fulfillable = false;
                } else {
                    allocationLine.setStatus("ALLOCATED");
                }
                allocation.addLine(allocationLine);
            }
        } catch (RuntimeException e) {
            reservationsMade.forEach(this::safeRelease);
            throw e;
        }

        if (fulfillable) {
            allocation.setStatus("FULFILLABLE");
            allocation.setShippers(cube(request, cubingMode, uomsBySku));
        } else {
            // Hold nothing for a non-fulfillable order; keep per-line diagnostics but drop the
            // (now released) reservation ids from the picks.
            reservationsMade.forEach(this::safeRelease);
            allocation.getLines().forEach(l -> l.setPicks(dropReservations(l.getPicks())));
            allocation.setStatus("NOT_FULFILLABLE");
            allocation.setShippers(List.of());
        }

        return AllocationView.from(allocations.save(allocation));
    }

    private List<ShipperAssignment> cube(AllocateOrderRequest request, String cubingMode,
                                         Map<UUID, List<UomDef>> uomsBySku) {
        List<ShipperDef> shippers = masterData.shippers(request.warehouseId());
        if ("ONE_TO_ONE".equals(cubingMode)) {
            return oneToOneCube(request, shippers, uomsBySku);
        }
        return appCube(request, shippers, uomsBySku);
    }

    private List<ShipperAssignment> appCube(AllocateOrderRequest request, List<ShipperDef> shippers,
                                            Map<UUID, List<UomDef>> uomsBySku) {
        MasterDataClient.FulfillmentConfig config = masterData.fulfillmentConfig(request.warehouseId());
        ShipperDef shipper = selectShipper(shippers, config.defaultShipperId());
        if (shipper == null) {
            log.warn("No shipper configured for warehouse {}; order {} left un-cubed",
                    request.warehouseId(), request.orderRef());
            return List.of();
        }
        List<CubingEngine.Item> items = new ArrayList<>();
        for (AllocateOrderRequest.Line line : request.lines()) {
            items.add(item(line.skuId(), line.qty().longValue(),
                    uomsBySku.computeIfAbsent(line.skuId(), masterData::skuUoms)));
        }
        return CubingEngine.appCube(items, shipper);
    }

    private List<ShipperAssignment> oneToOneCube(AllocateOrderRequest request, List<ShipperDef> shippers,
                                                 Map<UUID, List<UomDef>> uomsBySku) {
        if (request.cubeInstructions() == null) {
            return List.of();
        }
        List<ShipperAssignment> assignments = new ArrayList<>();
        int seq = 1;
        for (AllocateOrderRequest.CubeInstruction instruction : request.cubeInstructions()) {
            ShipperDef shipper = shippers.stream()
                    .filter(s -> s.code().equals(instruction.shipperCode()))
                    .findFirst()
                    .orElseThrow(() -> new CubingEngine.CannotCubeException(
                            "Unknown shipper code in cube instruction: " + instruction.shipperCode()));
            double volume = 0;
            double weight = shipper.tareWeightG() == null ? 0 : shipper.tareWeightG().doubleValue();
            List<ShipperAssignment.Content> contents = new ArrayList<>();
            if (instruction.contents() != null) {
                for (AllocateOrderRequest.Content c : instruction.contents()) {
                    CubingEngine.Item unit = item(c.skuId(), 1,
                            uomsBySku.computeIfAbsent(c.skuId(), masterData::skuUoms));
                    volume += unit.unitVolumeMm3() * c.qty().doubleValue();
                    weight += unit.unitWeightG() * c.qty().doubleValue();
                    contents.add(new ShipperAssignment.Content(c.skuId(), c.qty()));
                }
            }
            assignments.add(new ShipperAssignment(seq++, shipper.id(), shipper.code(), contents,
                    BigDecimal.valueOf(weight), BigDecimal.valueOf(volume)));
        }
        return assignments;
    }

    private static ShipperDef selectShipper(List<ShipperDef> shippers, UUID defaultShipperId) {
        List<ShipperDef> active = shippers.stream()
                .filter(s -> !"ARCHIVED".equals(s.status()))
                .toList();
        if (defaultShipperId != null) {
            return active.stream().filter(s -> defaultShipperId.equals(s.id())).findFirst()
                    .orElse(active.isEmpty() ? null : active.get(0));
        }
        return active.isEmpty() ? null : active.get(0);
    }

    private static CubingEngine.Item item(UUID skuId, long qty, List<UomDef> uoms) {
        UomDef base = uoms == null ? null : uoms.stream().filter(UomDef::baseUnit).findFirst().orElse(null);
        double volume = 0;
        double weight = 0;
        if (base != null) {
            if (base.lengthMm() != null && base.widthMm() != null && base.heightMm() != null) {
                volume = base.lengthMm().doubleValue() * base.widthMm().doubleValue() * base.heightMm().doubleValue();
            }
            if (base.weightG() != null) {
                weight = base.weightG().doubleValue();
            }
        }
        return new CubingEngine.Item(skuId, qty, volume, weight);
    }

    private static List<Pick> dropReservations(List<Pick> picks) {
        return picks.stream()
                .map(p -> new Pick(p.locationId(), p.qty(), null, p.uomBreakdown()))
                .toList();
    }

    private void safeRelease(UUID reservationId) {
        if (reservationId == null) {
            return;
        }
        try {
            inventory.release(reservationId);
        } catch (RuntimeException ex) {
            log.warn("Failed to release reservation {} during compensation", reservationId, ex);
        }
    }
}
