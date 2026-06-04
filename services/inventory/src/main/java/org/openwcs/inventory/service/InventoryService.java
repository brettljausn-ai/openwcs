package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.inventory.api.StockOverviewRow;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Reservation;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.ReservationRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock queries and reservation lifecycle (build.md §4.2). Reservations are checked
 * against available-to-promise under a pessimistic lock on the SKU's AVAILABLE rows,
 * so concurrent allocations cannot over-commit the same stock.
 */
@Service
public class InventoryService {

    private static final String HELD = "HELD";
    private static final String RELEASED = "RELEASED";
    private static final String CONSUMED = "CONSUMED";

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final HandlingUnitRepository handlingUnits;

    public InventoryService(
            StockRepository stock,
            ReservationRepository reservations,
            HandlingUnitRepository handlingUnits) {
        this.stock = stock;
        this.reservations = reservations;
        this.handlingUnits = handlingUnits;
    }

    @Transactional(readOnly = true)
    public Availability availability(UUID warehouseId, UUID skuId) {
        BigDecimal onHand = nz(stock.sumAvailable(warehouseId, skuId));
        BigDecimal reserved = nz(reservations.sumHeld(warehouseId, skuId));
        return new Availability(warehouseId, skuId, onHand, reserved, onHand.subtract(reserved));
    }

    /** Available-to-promise for a SKU at one location (used by pick-location allocation). */
    @Transactional(readOnly = true)
    public Availability availabilityAtLocation(UUID warehouseId, UUID skuId, UUID locationId) {
        BigDecimal onHand = nz(stock.sumAvailableAtLocation(warehouseId, skuId, locationId));
        BigDecimal reserved = nz(reservations.sumHeldAtLocation(warehouseId, skuId, locationId));
        return new Availability(warehouseId, skuId, onHand, reserved, onHand.subtract(reserved));
    }

    @Transactional(readOnly = true)
    public List<Stock> listStock(UUID warehouseId, UUID skuId) {
        return stock.findByWarehouseIdAndSkuId(warehouseId, skuId);
    }

    /**
     * Warehouse-wide stock overview: one row per bucket with on-hand qty, the quantity
     * currently HELD against it, and the resulting available (AVAILABLE buckets only).
     */
    @Transactional(readOnly = true)
    public List<StockOverviewRow> stockOverview(UUID warehouseId) {
        List<Stock> buckets = stock.findByWarehouseId(warehouseId);
        List<Reservation> held = reservations.findByWarehouseIdAndStatus(warehouseId, HELD);
        Map<UUID, String> huCodes = handlingUnits.findByWarehouseId(warehouseId).stream()
                .collect(Collectors.toMap(HandlingUnit::getHuId, HandlingUnit::getCode));
        return buckets.stream().map(bucket -> {
            BigDecimal reserved = held.stream()
                    .filter(r -> r.getSkuId().equals(bucket.getSkuId()))
                    .filter(r -> r.getLocationId() == null
                            || r.getLocationId().equals(bucket.getLocationId()))
                    .filter(r -> r.getHuId() == null || r.getHuId().equals(bucket.getHuId()))
                    .map(Reservation::getQty)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal available = "AVAILABLE".equals(bucket.getStatus())
                    ? bucket.getQty().subtract(reserved).max(BigDecimal.ZERO)
                    : BigDecimal.ZERO;
            String huCode = bucket.getHuId() == null ? null : huCodes.get(bucket.getHuId());
            return new StockOverviewRow(
                    bucket.getSkuId(), bucket.getLocationId(), bucket.getHuId(), huCode,
                    bucket.getStatus(), bucket.getQty(), reserved, available);
        }).toList();
    }

    @Transactional
    public Reservation reserve(ReserveCommand command) {
        // Lock the AVAILABLE rows so the ATP total is stable for the duration of this tx.
        // When a location is given the check is location-scoped (pick-location allocation);
        // otherwise it is SKU-wide across the warehouse.
        BigDecimal onHand;
        BigDecimal reserved;
        if (command.locationId() != null) {
            List<Stock> locked = stock.lockAvailableAtLocation(
                    command.warehouseId(), command.skuId(), command.locationId());
            onHand = locked.stream().map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            reserved = nz(reservations.sumHeldAtLocation(
                    command.warehouseId(), command.skuId(), command.locationId()));
        } else {
            List<Stock> locked = stock.lockAvailable(command.warehouseId(), command.skuId());
            onHand = locked.stream().map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            reserved = nz(reservations.sumHeld(command.warehouseId(), command.skuId()));
        }
        BigDecimal availableToPromise = onHand.subtract(reserved);

        if (command.qty().compareTo(availableToPromise) > 0) {
            throw new InsufficientStockException(command.skuId(), command.qty(), availableToPromise);
        }

        Reservation reservation = new Reservation();
        reservation.setWarehouseId(command.warehouseId());
        reservation.setSkuId(command.skuId());
        reservation.setBatchId(command.batchId());
        reservation.setLocationId(command.locationId());
        reservation.setHuId(command.huId());
        reservation.setOrderRef(command.orderRef());
        reservation.setCorrelationId(command.correlationId());
        reservation.setQty(command.qty());
        reservation.setStatus(HELD);
        reservation.setExpiresAt(command.expiresAt());
        return reservations.save(reservation);
    }

    @Transactional
    public Reservation release(UUID reservationId) {
        Reservation reservation = require(reservationId);
        if (HELD.equals(reservation.getStatus())) {
            reservation.setStatus(RELEASED);
        }
        return reservations.save(reservation);
    }

    @Transactional
    public Reservation consume(UUID reservationId) {
        Reservation reservation = require(reservationId);
        if (HELD.equals(reservation.getStatus())) {
            reservation.setStatus(CONSUMED);
        }
        return reservations.save(reservation);
    }

    @Transactional(readOnly = true)
    public List<Reservation> reservationsByOrder(String orderRef) {
        return reservations.findByOrderRef(orderRef);
    }

    private Reservation require(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
