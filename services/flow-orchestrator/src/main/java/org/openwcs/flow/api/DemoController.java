package org.openwcs.flow.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.flow.service.FlowDashboardSeedService;
import org.openwcs.flow.service.FlowDemoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for flow-orchestrator (build.md §4.8). Admin-only full operational reset for a
 * warehouse: purge all transactional flow state (device tasks, handling-unit routes and
 * topology observations), keeping the topology configuration. Invoked when demo mode is
 * turned off.
 */
@RestController
@RequestMapping("/api/flow/demo")
public class DemoController {

    private final FlowDemoService demo;
    private final FlowDashboardSeedService dashboardSeed;

    public DemoController(FlowDemoService demo, FlowDashboardSeedService dashboardSeed) {
        this.demo = demo;
        this.dashboardSeed = dashboardSeed;
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
     * Backfill today's scan-quality counters and one faulted equipment so the automation summary
     * (/reports/automation-summary) shows a plausible no-read rate and an availability dip on a
     * demo box. Admin-only, demo-only; rejected with 409 when demo mode is off.
     */
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
