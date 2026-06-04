package org.openwcs.masterdata.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.DemoSeedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode (build.md §4.8). Admin-only switch that seeds a sample catalog onto an empty,
 * host-free system and removes it again when switched off. Enable is rejected (409) unless the
 * catalog is empty (no host data).
 */
@RestController
@RequestMapping("/api/master-data/demo")
public class DemoController {

    private final DemoSeedService demo;

    public DemoController(DemoSeedService demo) {
        this.demo = demo;
    }

    @GetMapping
    public DemoStatusView status(@RequestParam(required = false) UUID warehouseId) {
        return demo.status(warehouseId);
    }

    /** Seed the demo catalog (admin-only). {@code warehouseId} scopes the demo shippers. */
    @PostMapping("/enable")
    public DemoResult enable(
            @RequestParam(required = false) UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        try {
            return demo.enable(warehouseId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Remove all demo data (admin-only). */
    @PostMapping("/disable")
    public DemoResult disable(@RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return demo.disable();
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
