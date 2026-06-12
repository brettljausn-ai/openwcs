package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.inventory.api.StockOverviewRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Reservation;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.ReservationRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock queries and reservation lifecycle (build.md §4.2). Reservations are checked
 * against available-to-promise under a pessimistic lock on the SKU's AVAILABLE rows,
 * so concurrent allocations cannot over-commit the same stock.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private static final String HELD = "HELD";
    private static final String RELEASED = "RELEASED";
    private static final String CONSUMED = "CONSUMED";

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final HandlingUnitRepository handlingUnits;
    private final MasterDataClient masterData;

    public InventoryService(
            StockRepository stock,
            ReservationRepository reservations,
            HandlingUnitRepository handlingUnits,
            MasterDataClient masterData) {
        this.stock = stock;
        this.reservations = reservations;
        this.handlingUnits = handlingUnits;
        this.masterData = masterData;
    }

    @Transactional(readOnly = true)
    public Availability availability(UUID warehouseId, UUID skuId) {
        BigDecimal onHand = nz(stock.sumAvailable(warehouseId, skuId));
        UUID unknownLocation = unknownLocationOrNull(warehouseId);
        if (unknownLocation != null) {
            // Stock at UNKNOWN (HUs booked there because nobody knows where they are) stays
            // visible in the overview but contributes ZERO to availability / ATP.
            List<Stock> quarantined = stock.findByWarehouseIdAndSkuIdAndLocationIdAndStatus(
                    warehouseId, skuId, unknownLocation, "AVAILABLE");
            if (!quarantined.isEmpty()) {
                BigDecimal excluded = quarantined.stream()
                        .map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
                onHand = onHand.subtract(excluded);
                log.debug("availability exclusion: {} stock row(s) totalling {} of sku {} sit at the"
                                + " UNKNOWN location {} and contribute zero to availability",
                        quarantined.size(), excluded, skuId, unknownLocation);
            }
        }
        BigDecimal reserved = nz(reservations.sumHeld(warehouseId, skuId));
        return new Availability(warehouseId, skuId, onHand, reserved, onHand.subtract(reserved));
    }

    /** Available-to-promise for a SKU at one location (used by pick-location allocation). */
    @Transactional(readOnly = true)
    public Availability availabilityAtLocation(UUID warehouseId, UUID skuId, UUID locationId) {
        UUID unknownLocation = unknownLocationOrNull(warehouseId);
        if (unknownLocation != null && unknownLocation.equals(locationId)) {
            log.debug("availability exclusion: location {} is the UNKNOWN location of warehouse {};"
                            + " sku {} availability there is reported as zero (never allocatable)",
                    locationId, warehouseId, skuId);
            return new Availability(warehouseId, skuId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
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
        UUID unknownLocation = unknownLocationOrNull(command.warehouseId());
        if (command.locationId() != null) {
            if (command.locationId().equals(unknownLocation)) {
                // Stock at UNKNOWN can never be reserved: nobody knows where it physically is.
                log.warn("reservation rejected: {} of sku {} requested for order {} at location {} but that"
                                + " is the UNKNOWN location of warehouse {}; stock there can never be reserved,"
                                + " the caller gets an insufficient-stock error and no hold is placed",
                        command.qty(), command.skuId(), command.orderRef(),
                        command.locationId(), command.warehouseId());
                throw new InsufficientStockException(command.skuId(), command.qty(), BigDecimal.ZERO);
            }
            List<Stock> locked = stock.lockAvailableAtLocation(
                    command.warehouseId(), command.skuId(), command.locationId());
            onHand = locked.stream().map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            reserved = nz(reservations.sumHeldAtLocation(
                    command.warehouseId(), command.skuId(), command.locationId()));
        } else {
            List<Stock> locked = stock.lockAvailable(command.warehouseId(), command.skuId());
            List<Stock> allocatable = locked;
            if (unknownLocation != null) {
                allocatable = locked.stream()
                        .filter(s -> !unknownLocation.equals(s.getLocationId()))
                        .toList();
                int excluded = locked.size() - allocatable.size();
                if (excluded > 0) {
                    log.debug("reservation exclusion: {} stock row(s) of sku {} at the UNKNOWN location {}"
                                    + " excluded from the available-to-promise check",
                            excluded, command.skuId(), unknownLocation);
                }
            }
            onHand = allocatable.stream().map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            reserved = nz(reservations.sumHeld(command.warehouseId(), command.skuId()));
        }
        BigDecimal availableToPromise = onHand.subtract(reserved);

        if (command.qty().compareTo(availableToPromise) > 0) {
            log.warn("reservation rejected: {} of sku {} requested for order {}{} but only {} available to promise"
                            + " (on hand {}, held {}); the caller gets an insufficient-stock error and no hold is placed",
                    command.qty(), command.skuId(), command.orderRef(),
                    command.locationId() == null ? "" : " at location " + command.locationId(),
                    availableToPromise, onHand, reserved);
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
        Reservation saved = reservations.save(reservation);
        log.info("reservation held: {} of sku {} for order {} (reservation {}){}"
                        + " because available to promise {} covered the request",
                saved.getQty(), saved.getSkuId(), saved.getOrderRef(), saved.getId(),
                saved.getLocationId() == null ? "" : " at location " + saved.getLocationId(),
                availableToPromise);
        return saved;
    }

    @Transactional
    public Reservation release(UUID reservationId) {
        Reservation reservation = require(reservationId);
        if (HELD.equals(reservation.getStatus())) {
            reservation.setStatus(RELEASED);
            log.info("reservation released: {} of sku {} for order {} (reservation {})"
                            + " because the hold is no longer needed; the qty counts as available to promise again",
                    reservation.getQty(), reservation.getSkuId(), reservation.getOrderRef(), reservationId);
        } else {
            log.warn("reservation release skipped: reservation {} for order {} is {} not HELD;"
                            + " status left unchanged (release is idempotent)",
                    reservationId, reservation.getOrderRef(), reservation.getStatus());
        }
        return reservations.save(reservation);
    }

    @Transactional
    public Reservation consume(UUID reservationId) {
        Reservation reservation = require(reservationId);
        if (HELD.equals(reservation.getStatus())) {
            reservation.setStatus(CONSUMED);
            log.info("reservation consumed: {} of sku {} for order {} (reservation {})"
                            + " because the reserved stock was taken by the pick",
                    reservation.getQty(), reservation.getSkuId(), reservation.getOrderRef(), reservationId);
        } else {
            log.warn("reservation consume skipped: reservation {} for order {} is {} not HELD;"
                            + " status left unchanged (consume is idempotent)",
                    reservationId, reservation.getOrderRef(), reservation.getStatus());
        }
        return reservations.save(reservation);
    }

    @Transactional(readOnly = true)
    public List<Reservation> reservationsByOrder(String orderRef) {
        return reservations.findByOrderRef(orderRef);
    }

    /**
     * The warehouse's UNKNOWN-location id, or null when master-data cannot be reached. The lookup
     * is best-effort (and cached per warehouse in the client) so availability reads do not fail
     * with master-data down; skipping the exclusion can only transiently OVERstate availability.
     */
    private UUID unknownLocationOrNull(UUID warehouseId) {
        try {
            return masterData.unknownLocationId(warehouseId);
        } catch (MasterDataUnavailableException e) {
            log.warn("UNKNOWN-location exclusion skipped for warehouse {} because master-data is"
                            + " unreachable ({}); stock at UNKNOWN may transiently count as available",
                    warehouseId, e.getMessage());
            return null;
        }
    }

    private Reservation require(UUID reservationId) {
        return reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
