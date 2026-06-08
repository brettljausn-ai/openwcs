package org.openwcs.counting.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountTaskRepository;
import org.openwcs.counting.service.CountLineView;
import org.openwcs.counting.service.CountingService;
import org.openwcs.counting.service.ReconciliationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cycle-count task API: create/list/claim count tasks, submit counts, reconcile (approve →
 * adjustment, out-of-tolerance → recount), and query results. The acting identity is taken from the
 * gateway-forwarded {@code X-Auth-User}; the {@code actor}/{@code operator} params are a fallback for
 * direct calls.
 */
@RestController
@RequestMapping("/api/counting/tasks")
public class CountTaskController {

    private final CountingService counting;
    private final CountTaskRepository tasks;

    public CountTaskController(CountingService counting, CountTaskRepository tasks) {
        this.counting = counting;
        this.tasks = tasks;
    }

    @PostMapping
    public ResponseEntity<CountTaskView> create(
            @Valid @RequestBody CreateCountTaskRequest request,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        requireWarehouse(warehouses, request.warehouseId());
        CountTask task = counting.generate(request.toCommand());
        return ResponseEntity
                .created(URI.create("/api/counting/tasks/" + task.getId()))
                .body(CountTaskView.from(task));
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }

    @GetMapping
    public List<CountTaskView> list(@RequestParam UUID warehouseId,
                                    @RequestParam(required = false) String status) {
        List<CountTask> result = status == null
                ? tasks.findByWarehouseId(warehouseId)
                : tasks.findByWarehouseIdAndStatus(warehouseId, status);
        return result.stream().map(CountTaskView::from).toList();
    }

    @GetMapping("/{taskId}")
    public CountTaskView get(@PathVariable UUID taskId) {
        return CountTaskView.from(counting.task(taskId));
    }

    /** The lines an operator sees (BLIND hides expected qty/variance until reconciled). */
    @GetMapping("/{taskId}/lines")
    public List<CountLineView> lines(@PathVariable UUID taskId) {
        return counting.linesFor(taskId);
    }

    @PostMapping("/{taskId}/claim")
    public CountTaskView claim(@PathVariable UUID taskId,
                               @RequestHeader(name = "X-Auth-User", required = false) String authUser,
                               @RequestParam(required = false) String operator) {
        return CountTaskView.from(counting.claim(taskId, actor(authUser, operator)));
    }

    @PostMapping("/{taskId}/counts")
    public CountTaskView submit(@PathVariable UUID taskId,
                                @Valid @RequestBody SubmitCountsRequest request,
                                @RequestHeader(name = "X-Auth-User", required = false) String authUser,
                                @RequestParam(required = false) String operator) {
        return CountTaskView.from(counting.submitCounts(taskId, request.toEntries(), actor(authUser, operator)));
    }

    @PostMapping("/{taskId}/reconcile")
    public ReconciliationResult reconcile(@PathVariable UUID taskId,
                                          @RequestHeader(name = "X-Auth-User", required = false) String authUser,
                                          @RequestParam(required = false) String actor) {
        return counting.reconcile(taskId, actor(authUser, actor));
    }

    /**
     * Record one at-station blind count for a line. The operator never sees the system qty or their
     * prior count; the result tells them what to do next: ACCEPTED (done), RECOUNT (count again), or
     * ADJUSTED (variance confirmed and posted to the host).
     */
    @PostMapping("/{taskId}/lines/{lineId}/station-count")
    public CountingService.StationCountResult stationCount(@PathVariable UUID taskId,
                                                           @PathVariable UUID lineId,
                                                           @Valid @RequestBody StationCountRequest request,
                                                           @RequestHeader(name = "X-Auth-User", required = false) String authUser,
                                                           @RequestParam(required = false) String operator) {
        return counting.recordStationCount(taskId, lineId, request.countedQty(), actor(authUser, operator));
    }

    /** Reconciled results — every line with its final variance/adjustment outcome (expected shown). */
    @GetMapping("/{taskId}/results")
    public List<CountLineView> results(@PathVariable UUID taskId) {
        return counting.rawLines(taskId).stream().map(l -> CountLineView.of(l, false)).toList();
    }

    /** Delete a count task that has not started yet (status OPEN); 409 once it is active. */
    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
        try {
            counting.deleteTask(taskId);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static String actor(String forwarded, String fallback) {
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return fallback == null || fallback.isBlank() ? "system" : fallback;
    }
}
