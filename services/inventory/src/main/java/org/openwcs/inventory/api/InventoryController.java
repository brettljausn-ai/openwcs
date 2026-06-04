package org.openwcs.inventory.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.domain.Reservation;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.Availability;
import org.openwcs.inventory.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stock queries, availability, and reservations (build.md §4.2). */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;
    private final StockRepository stock;
    private final HandlingUnitRepository handlingUnits;

    public InventoryController(
            InventoryService service, StockRepository stock, HandlingUnitRepository handlingUnits) {
        this.service = service;
        this.stock = stock;
        this.handlingUnits = handlingUnits;
    }

    /** Current stock buckets for a SKU in a warehouse. */
    @GetMapping("/stock")
    public List<StockView> stock(@RequestParam UUID warehouseId, @RequestParam UUID skuId) {
        return service.listStock(warehouseId, skuId).stream().map(StockView::from).toList();
    }

    /** Warehouse-wide stock overview: every bucket with on-hand, reserved (HELD) and available qty. */
    @GetMapping("/stock/overview")
    public List<StockOverviewRow> stockOverview(@RequestParam UUID warehouseId) {
        return service.stockOverview(warehouseId);
    }

    /** Available-to-promise summary (on-hand − reserved); pass locationId for a pick-location ATP. */
    @GetMapping("/availability")
    public Availability availability(
            @RequestParam UUID warehouseId,
            @RequestParam UUID skuId,
            @RequestParam(required = false) UUID locationId) {
        return locationId != null
                ? service.availabilityAtLocation(warehouseId, skuId, locationId)
                : service.availability(warehouseId, skuId);
    }

    /**
     * Occupancy check for a set of locations — counts stock rows and handling units that sit at
     * them. The UI uses this to confirm a storage block is empty before requesting its deletion.
     */
    @PostMapping("/occupancy")
    public OccupancyResult occupancy(@RequestBody OccupancyRequest request) {
        List<UUID> locationIds = request.locationIds();
        if (locationIds == null || locationIds.isEmpty()) {
            return new OccupancyResult(0, 0);
        }
        return new OccupancyResult(
                stock.countByLocationIdIn(locationIds),
                handlingUnits.countByLocationIdIn(locationIds));
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationView> reserve(@Valid @RequestBody ReserveRequest request) {
        Reservation reservation = service.reserve(request.toCommand());
        return ResponseEntity
                .created(URI.create("/api/inventory/reservations/" + reservation.getId()))
                .body(ReservationView.from(reservation));
    }

    @GetMapping("/reservations")
    public List<ReservationView> reservationsByOrder(@RequestParam String orderRef) {
        return service.reservationsByOrder(orderRef).stream().map(ReservationView::from).toList();
    }

    /** Release a held reservation (cancel the allocation). */
    @PostMapping("/reservations/{reservationId}/release")
    public ReservationView release(@PathVariable UUID reservationId) {
        return ReservationView.from(service.release(reservationId));
    }

    /** Mark a held reservation consumed (e.g. after the pick is confirmed). */
    @PostMapping("/reservations/{reservationId}/consume")
    public ReservationView consume(@PathVariable UUID reservationId) {
        return ReservationView.from(service.consume(reservationId));
    }
}
