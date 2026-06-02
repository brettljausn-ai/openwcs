package org.openwcs.orders.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.service.OrderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Outbound order management (build.md §4.6). */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderView> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderView order = service.create(request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public PageResponse<OrderView> list(
            @RequestParam UUID warehouseId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(warehouseId, status, PageRequest.of(page, size, Sort.by("createdAt").descending()));
    }

    /** Release management: most-urgent-first queue of orders awaiting release. */
    @GetMapping("/release-queue")
    public List<OrderView> releaseQueue(@RequestParam UUID warehouseId) {
        return service.releaseQueue(warehouseId);
    }

    /** Release all CREATED orders due within the given window (default: already due). */
    @PostMapping("/release-due")
    public List<OrderView> releaseDue(
            @RequestParam UUID warehouseId,
            @RequestParam(defaultValue = "0") long withinMinutes) {
        return service.releaseDue(warehouseId, Instant.now().plus(Duration.ofMinutes(withinMinutes)));
    }

    @PostMapping("/{id}/release")
    public OrderView release(@PathVariable UUID id) {
        return service.release(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }

    @PostMapping("/{id}/ship")
    public OrderView ship(@PathVariable UUID id) {
        return service.ship(id);
    }

    /**
     * Post a stock transaction beneath a line (receipt / pick / count / adjustment by order
     * type). The actor recorded for audit is the gateway-forwarded authenticated user
     * ({@code X-Auth-User}); the request-body {@code actor} is only a fallback for direct calls.
     */
    @PostMapping("/{id}/lines/{lineNo}/transactions")
    public OrderView postTransaction(
            @PathVariable UUID id,
            @PathVariable int lineNo,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser,
            @Valid @RequestBody PostTransactionRequest request) {
        String actor = (authUser != null && !authUser.isBlank()) ? authUser : request.actor();
        return service.postTransaction(id, lineNo, request, actor);
    }
}
