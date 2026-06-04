package org.openwcs.orders.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.orders.service.DemoResetService;
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

    public DemoController(DemoResetService demo) {
        this.demo = demo;
    }

    /** Full operational reset for a warehouse (admin-only). */
    @PostMapping("/clear")
    public DemoClearResult clear(
            @RequestParam UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return demo.clear(warehouseId);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
