package org.openwcs.inventory.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.domain.Reservation;
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

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    /** Current stock buckets for a SKU in a warehouse. */
    @GetMapping("/stock")
    public List<StockView> stock(@RequestParam UUID warehouseId, @RequestParam UUID skuId) {
        return service.listStock(warehouseId, skuId).stream().map(StockView::from).toList();
    }

    /** Available-to-promise summary (on-hand − reserved). */
    @GetMapping("/availability")
    public Availability availability(@RequestParam UUID warehouseId, @RequestParam UUID skuId) {
        return service.availability(warehouseId, skuId);
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
