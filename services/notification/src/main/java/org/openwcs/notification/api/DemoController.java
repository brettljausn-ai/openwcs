package org.openwcs.notification.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.notification.service.DemoDashboardSeedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for notification (build.md §4.8). Admin-only backfill of representative alert_event
 * rows when demo mode is turned on (so the andon board and alert-system-health show plausible
 * numbers on a demo box), and removal of those same demo rows ({@code /clear}) when it is turned
 * off so the boards return to empty.
 */
@RestController
@RequestMapping("/api/notification/demo")
public class DemoController {

    private final DemoDashboardSeedService dashboardSeed;

    public DemoController(DemoDashboardSeedService dashboardSeed) {
        this.dashboardSeed = dashboardSeed;
    }

    /** Backfill andon + alert-health demo data for a warehouse (admin-only, demo-only). */
    @PostMapping("/seed-dashboard")
    public DemoDashboardSeedResult seedDashboard(
            @RequestParam UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        try {
            return dashboardSeed.seed(warehouseId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Remove the demo alert rows the seeder backfilled (admin-only), invoked when demo mode is turned
     * off. Not demo-gated: the off-toggle must always be able to complete the teardown.
     */
    @PostMapping("/clear")
    public DemoClearResult clear(
            @RequestParam UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return dashboardSeed.clear(warehouseId);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
