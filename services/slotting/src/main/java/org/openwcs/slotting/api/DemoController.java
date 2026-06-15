package org.openwcs.slotting.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.service.DemoDashboardSeedService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for slotting (build.md §4.8). Admin-only, demo-only backfill of dashboard-relevant data
 * (ABC velocity + daily picks + replenishment tasks) so /velocity/abc and /replenishment/dashboard
 * show plausible numbers on a fresh demo box. No effect when demo mode is off.
 */
@RestController
@RequestMapping("/api/slotting/demo")
public class DemoController {

    private final DemoDashboardSeedService dashboardSeed;

    public DemoController(DemoDashboardSeedService dashboardSeed) {
        this.dashboardSeed = dashboardSeed;
    }

    /** Backfill ABC + replenishment dashboard data for a warehouse (admin-only, demo-only). */
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

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
