package org.openwcs.inventory.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.inventory.service.DemoSeedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for inventory (build.md §4.8). Admin-only writes that register demo handling units
 * and put sample stock in them (seed), and remove that demo data again (clear). The UI passes the
 * existing master-data SKU ids, location ids and demo HU type id seeded on the master-data side.
 */
@RestController
@RequestMapping("/api/inventory/demo")
public class DemoController {

    private final DemoSeedService demo;

    public DemoController(DemoSeedService demo) {
        this.demo = demo;
    }

    /** Seed demo handling units + stock (admin-only). */
    @PostMapping("/seed")
    public DemoSeedResult seed(
            @RequestBody DemoSeedRequest request,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return demo.seed(request);
    }

    /** Remove demo handling units + their stock for a warehouse (admin-only). */
    @PostMapping("/clear")
    public DemoSeedResult clear(
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
