package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.api.ConfirmPutRequest;
import org.openwcs.gtp.api.NotFoundException;
import org.openwcs.gtp.api.PresentStockRequest;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.PutInstructionRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.openwcs.gtp.repo.WorkCycleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pick-and-put work cycle (ADR 0006). Presenting a stock HU (SKU + qty) at a station matches
 * the SKU against open per-destination demand across the station's ORDER nodes and greedily
 * allocates the available stock to them (most-needed first), emitting a put-list of
 * {@link PutInstruction}s — one stock HU serving many destinations (the goods-to-person batch).
 * Confirming a put decrements the cycle's remaining stock and the destination's demand, completing
 * the destination when fully putted. Surplus demand stays OPEN for the next presentation
 * (short-pick handling). The logic is identical for ORDER_LOCATION and PUT_WALL stations; the only
 * difference is what an ORDER node represents physically.
 */
@Service
public class WorkCycleService {

    private final GtpStationRepository stations;
    private final StationNodeRepository nodes;
    private final DestinationDemandRepository demands;
    private final WorkCycleRepository cycles;
    private final PutInstructionRepository puts;

    public WorkCycleService(GtpStationRepository stations,
                            StationNodeRepository nodes,
                            DestinationDemandRepository demands,
                            WorkCycleRepository cycles,
                            PutInstructionRepository puts) {
        this.stations = stations;
        this.nodes = nodes;
        this.demands = demands;
        this.cycles = cycles;
        this.puts = puts;
    }

    /**
     * Present a stock HU at the station and build its put-list. Open demand for the SKU across the
     * station's ORDER nodes is filled most-needed-first until either all demand is met or the
     * presented qty is exhausted.
     */
    @Transactional
    public WorkCycle present(UUID stationId, PresentStockRequest request) {
        GtpStation station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("station", stationId));

        UUID stockNodeId = resolveStockNode(stationId, request.stockNodeId());

        WorkCycle cycle = new WorkCycle();
        cycle.setStationId(stationId);
        cycle.setStockNodeId(stockNodeId);
        cycle.setStockHuId(request.stockHuId());
        cycle.setSkuId(request.skuId());
        cycle.setPresentedQty(request.qty());
        cycle.setRemainingQty(request.qty());
        cycles.save(cycle);

        BigDecimal available = request.qty();
        List<DestinationDemand> open = demands.findOpenForStationAndSku(stationId, request.skuId());
        for (DestinationDemand demand : open) {
            if (available.signum() <= 0) {
                break;
            }
            BigDecimal need = demand.remaining();
            BigDecimal put = need.min(available);
            if (put.signum() <= 0) {
                continue;
            }
            StationNode dest = nodes.findById(demand.getStationNodeId())
                    .orElseThrow(() -> new NotFoundException("station node", demand.getStationNodeId()));

            PutInstruction instruction = new PutInstruction();
            instruction.setWorkCycleId(cycle.getId());
            instruction.setDestinationNodeId(dest.getId());
            instruction.setDestinationDemandId(demand.getId());
            instruction.setOrderRef(demand.getOrderRef());
            instruction.setOrderLineId(demand.getOrderLineId());
            instruction.setOrderHuId(dest.getOrderHuId());
            instruction.setPutLightId(dest.getPutLightId());
            instruction.setQty(put);
            puts.save(instruction);

            available = available.subtract(put);
        }
        // remaining_qty reflects stock not yet reserved by put instructions; it is decremented on
        // confirmation. At present time everything assigned to a put is still in the HU.
        return cycle;
    }

    /**
     * Confirm a put. With no qty (or the full lit qty) the instruction is CONFIRMED; a smaller qty
     * is a SHORT put. Decrements the cycle's remaining stock and the destination's putted qty, and
     * completes the destination demand when fully satisfied.
     */
    @Transactional
    public PutInstruction confirm(UUID putInstructionId, ConfirmPutRequest request) {
        PutInstruction instruction = puts.findById(putInstructionId)
                .orElseThrow(() -> new NotFoundException("put instruction", putInstructionId));
        if (!"OPEN".equals(instruction.getStatus())) {
            throw new IllegalStateException("put instruction is not OPEN: " + instruction.getStatus());
        }

        BigDecimal confirmed = request != null && request.qty() != null ? request.qty() : instruction.getQty();
        if (confirmed.signum() <= 0) {
            throw new IllegalArgumentException("confirmed qty must be positive");
        }
        if (confirmed.compareTo(instruction.getQty()) > 0) {
            throw new IllegalArgumentException("confirmed qty exceeds the lit quantity");
        }

        WorkCycle cycle = cycles.findById(instruction.getWorkCycleId())
                .orElseThrow(() -> new NotFoundException("work cycle", instruction.getWorkCycleId()));
        DestinationDemand demand = demands.findById(instruction.getDestinationDemandId())
                .orElseThrow(() -> new NotFoundException("destination demand",
                        instruction.getDestinationDemandId()));

        instruction.setConfirmedQty(confirmed);
        instruction.setStatus(confirmed.compareTo(instruction.getQty()) < 0 ? "SHORT" : "CONFIRMED");

        demand.setPuttedQty(demand.getPuttedQty().add(confirmed));
        if (demand.remaining().signum() <= 0) {
            demand.setStatus("COMPLETED");
        }

        cycle.setRemainingQty(cycle.getRemainingQty().subtract(confirmed));
        if (allResolved(cycle.getId())) {
            cycle.setStatus("COMPLETED");
        }
        return instruction;
    }

    /** Close a cycle (e.g. the stock HU is sent away); any OPEN puts are cancelled. */
    @Transactional
    public WorkCycle close(UUID cycleId) {
        WorkCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundException("work cycle", cycleId));
        for (PutInstruction p : puts.findByWorkCycleId(cycleId)) {
            if ("OPEN".equals(p.getStatus())) {
                p.setStatus("CANCELLED");
            }
        }
        if (!"COMPLETED".equals(cycle.getStatus())) {
            cycle.setStatus("CLOSED");
        }
        return cycle;
    }

    @Transactional(readOnly = true)
    public WorkCycle requireCycle(UUID cycleId) {
        return cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundException("work cycle", cycleId));
    }

    @Transactional(readOnly = true)
    public List<PutInstruction> putsOf(UUID cycleId) {
        return puts.findByWorkCycleId(cycleId);
    }

    @Transactional(readOnly = true)
    public String modeOf(UUID stationId) {
        return stations.findById(stationId).map(GtpStation::getMode).orElse(null);
    }

    private boolean allResolved(UUID cycleId) {
        return puts.findByWorkCycleId(cycleId).stream().noneMatch(p -> "OPEN".equals(p.getStatus()));
    }

    private UUID resolveStockNode(UUID stationId, UUID requested) {
        List<StationNode> stockNodes = nodes.findByStationIdAndRole(stationId, "STOCK");
        if (stockNodes.isEmpty()) {
            throw new IllegalStateException("station has no STOCK node configured");
        }
        if (requested == null) {
            return stockNodes.get(0).getId();
        }
        return stockNodes.stream()
                .map(StationNode::getId)
                .filter(requested::equals)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "stockNodeId is not a STOCK node of this station: " + requested));
    }
}
