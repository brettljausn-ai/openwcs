package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.service.ReplenishmentDashboardService;
import org.openwcs.slotting.service.ReplenishmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Replenishment planning + task list (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/replenishment")
public class ReplenishmentController {

    private final ReplenishmentService replenishment;
    private final ReplenishmentTaskRepository tasks;
    private final ReplenishmentDashboardService dashboard;

    public ReplenishmentController(ReplenishmentService replenishment, ReplenishmentTaskRepository tasks,
                                   ReplenishmentDashboardService dashboard) {
        this.replenishment = replenishment;
        this.tasks = tasks;
        this.dashboard = dashboard;
    }

    /** Reactive below-min pass (raises EMERGENCY/SCHEDULED tasks). */
    @PostMapping("/plan")
    public List<ReplenishmentTask> plan(@RequestParam UUID warehouseId) {
        return replenishment.planBelowMin(warehouseId);
    }

    /** Opportunistic top-off-to-max pass. */
    @PostMapping("/top-off")
    public List<ReplenishmentTask> topOff(@RequestParam UUID warehouseId) {
        return replenishment.topOff(warehouseId);
    }

    @GetMapping("/tasks")
    public List<ReplenishmentTask> list(@RequestParam UUID warehouseId,
                                        @RequestParam(defaultValue = "PLANNED") String status) {
        return tasks.findByWarehouseIdAndStatus(warehouseId, status);
    }

    /** Read-only replenishment health snapshot for a warehouse (Dashboards &amp; alerting). */
    @GetMapping("/dashboard")
    public ReplenishmentDashboardView dashboard(
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestParam UUID warehouseId) {
        requireWarehouse(warehouses, warehouseId);
        return dashboard.build(warehouseId);
    }

    /** 403 if the caller is warehouse-scoped and the target is outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
