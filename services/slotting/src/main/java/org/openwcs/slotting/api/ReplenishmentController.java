package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.service.DispatchService;
import org.openwcs.slotting.service.ReplenishmentDashboardService;
import org.openwcs.slotting.service.ReplenishmentReservationService;
import org.openwcs.slotting.service.ReplenishmentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final DispatchService dispatch;
    private final ReplenishmentReservationService reservations;

    public ReplenishmentController(ReplenishmentService replenishment, ReplenishmentTaskRepository tasks,
                                   ReplenishmentDashboardService dashboard, DispatchService dispatch,
                                   ReplenishmentReservationService reservations) {
        this.replenishment = replenishment;
        this.tasks = tasks;
        this.dashboard = dashboard;
        this.dispatch = dispatch;
        this.reservations = reservations;
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

    /**
     * Dispatch a PLANNED replenishment task to flow-orchestrator as a physical move (operator/admin).
     * Resolves a reserve source for the SKU (best-effort) and moves it to the pick face, then flips
     * the task to DISPATCHED. 409 if it is not PLANNED; the flow call is best-effort.
     */
    @PostMapping("/{taskId}/dispatch")
    public ReplenishmentTask dispatch(
            @PathVariable UUID taskId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        DispatchAccess.requireOperator(roles);
        ReplenishmentTask task = tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("replenishment task " + taskId + " not found"));
        requireWarehouse(warehouses, task.getWarehouseId());
        return dispatch.dispatchReplenishment(taskId);
    }

    /**
     * Atomically claim (reserve) the next eligible collection task in an area for the calling operator
     * (operator/admin). The task's destination pick face must be inside the area subtree; eligible
     * tasks are PLANNED and unreserved, ordered EMERGENCY-first then oldest. Concurrent claims get
     * different tasks (FOR UPDATE SKIP LOCKED). Returns {found:false} when nothing is available.
     */
    @PostMapping("/claim-next")
    public ClaimNextResult claimNext(
            @RequestBody ClaimNextRequest request,
            @RequestHeader(name = "X-Auth-User", required = false) String user,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        DispatchAccess.requireOperator(roles);
        requireWarehouse(warehouses, request.warehouseId());
        return reservations.claimNext(request.warehouseId(), request.areaId(), user, request.instanceId());
    }

    /**
     * Release every uncommitted reservation held by a runtime instance (operator logged out / handheld
     * gone). Only PLANNED tasks that have not started are freed; idempotent. Returns the count freed.
     */
    @PostMapping("/release")
    public ReleaseResult release(
            @RequestBody ReleaseRequest request,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        DispatchAccess.requireOperator(roles);
        return new ReleaseResult(reservations.release(request.instanceId()));
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
