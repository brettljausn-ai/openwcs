package org.openwcs.orders.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.orders.service.DemoResetService;
import org.openwcs.orders.service.DemoSeedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for order management (build.md §4.8). Admin-only full operational reset invoked
 * when demo mode is turned off: purges every order for a warehouse (lines and line
 * transactions cascade) and drains the order outbox, keeping infrastructure and master data.
 */
@RestController
@RequestMapping("/api/orders/demo")
public class DemoController {

    private final DemoResetService demo;
    private final DemoSeedService seed;

    public DemoController(DemoResetService demo, DemoSeedService seed) {
        this.demo = demo;
        this.seed = seed;
    }

    /** Full operational reset for a warehouse (admin-only). */
    @PostMapping("/clear")
    public DemoClearResult clear(
            @RequestParam UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return demo.clear(warehouseId);
    }

    /**
     * Bulk-create demo orders for a warehouse (the inbound/outbound "Add 10" buttons). Surfaced
     * only while demo mode is on; rejected with 409 if the demo catalog is absent.
     */
    @PostMapping("/seed")
    public DemoSeedResult seed(
            @RequestParam UUID warehouseId,
            @RequestParam(defaultValue = "OUTBOUND") String type,
            @RequestParam(defaultValue = "10") int count) {
        try {
            return seed.seed(warehouseId, type, count);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
