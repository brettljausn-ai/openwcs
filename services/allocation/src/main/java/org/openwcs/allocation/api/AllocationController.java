package org.openwcs.allocation.api;

import jakarta.validation.Valid;
import org.openwcs.allocation.service.AllocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Allocate + cube outbound orders (ADR 0002). */
@RestController
@RequestMapping("/api/allocation")
public class AllocationController {

    private final AllocationService service;

    public AllocationController(AllocationService service) {
        this.service = service;
    }

    /**
     * Allocate (and cube) an order. Returns the plan with status FULFILLABLE or
     * NOT_FULFILLABLE; idempotent for an already-FULFILLABLE order.
     */
    @PostMapping("/orders")
    public AllocationView allocate(@Valid @RequestBody AllocateOrderRequest request) {
        return service.allocate(request);
    }

    @GetMapping("/orders/{orderRef}")
    public AllocationView get(@PathVariable String orderRef) {
        return service.get(orderRef);
    }

    /** Cancel an order's allocation, releasing all held reservations. */
    @PostMapping("/orders/{orderRef}/cancel")
    public AllocationView cancel(@PathVariable String orderRef) {
        return service.cancel(orderRef);
    }
}
