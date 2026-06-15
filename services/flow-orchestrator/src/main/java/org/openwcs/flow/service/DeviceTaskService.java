package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.flow.api.DeviceTaskNotFoundException;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.client.TxLogClient;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

/**
 * Turns a requested step into a device task and dispatches it to the equipment adapter
 * (build.md §4.5/§8). Records REQUESTED → DISPATCHED → COMPLETED/FAILED. Dispatch is
 * synchronous against the (simulator) adapter; a failed adapter call records FAILED rather
 * than losing the task.
 */
@Service
public class DeviceTaskService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTaskService.class);

    /**
     * Commands that physically relocate a handling unit (vs CONVEY transport): each terminal one
     * emits a {@code HandlingUnitMoved} audit event. AutoStore bin variants included.
     */
    private static final Set<String> MOVE_COMMANDS = Set.of(
            "STORE", "BIN_STORE", "RETRIEVE", "BIN_RETRIEVE", "RELOCATE", "BIN_RELOCATE");

    private final DeviceTaskRepository tasks;
    private final DeviceClient deviceClient;
    private final InductionQueueService induction;
    private final TxLogClient txlog;

    /**
     * {@code induction} is injected {@link Lazy} to break the dispatch↔callback cycle:
     * {@link InductionQueueService} dispatches device tasks through this service, and this service
     * advances induction entries from the §3b callback. The lazy proxy is only dereferenced inside
     * {@link #completeFromCallback}, after both beans are constructed.
     */
    public DeviceTaskService(DeviceTaskRepository tasks, DeviceClient deviceClient,
                             @Lazy InductionQueueService induction, TxLogClient txlog) {
        this.tasks = tasks;
        this.deviceClient = deviceClient;
        this.induction = induction;
        this.txlog = txlog;
    }

    @Transactional
    public DeviceTaskView request(RequestDeviceTask request, String actor) {
        DeviceTask task = new DeviceTask();
        task.setWarehouseId(request.warehouseId());
        task.setFamily(request.family());
        task.setEquipmentId(request.equipmentId());
        task.setCommand(request.command());
        if (request.payload() != null) {
            task.setPayload(request.payload());
        }
        task.setCorrelationId(request.correlationId());
        task.setActor(actor);
        task.setStatus("REQUESTED");
        tasks.saveAndFlush(task); // assign the id before dispatch
        log.info("device task {} created: {} {} for hu {} (correlation {}, actor {})",
                task.getId(), request.family(), request.command(), huOf(task), request.correlationId(), actor);

        task.setStatus("DISPATCHED");
        try {
            DeviceClient.DeviceResult result = deviceClient.execute(task);
            if (result != null && ("ACCEPTED".equals(result.status()) || "DISPATCHED".equals(result.status()))) {
                // Asynchronous device: it acked the dispatch and will POST the terminal result back to
                // /{id}/result (see completeFromCallback). Leave the task DISPATCHED until then.
                task.setDetail(result.detail());
                log.info("device task {} ({} for hu {}) acked by adapter, awaiting result callback",
                        task.getId(), task.getCommand(), huOf(task));
            } else if (result != null && "COMPLETED".equals(result.status())) {
                task.setStatus("COMPLETED");
                task.setDetail(result.detail());
                task.setResult(result.resultPayload());
                log.info("device task {} ({} for hu {}) completed synchronously by adapter",
                        task.getId(), task.getCommand(), huOf(task));
                auditMove(task, true);
            } else {
                task.setStatus("FAILED");
                task.setDetail(result == null ? "adapter returned no result" : result.detail());
                task.setResult(result == null ? null : result.resultPayload());
                log.warn("device task {} ({} for hu {}) FAILED at dispatch: {} (adapter rejected it)",
                        task.getId(), task.getCommand(), huOf(task), task.getDetail());
                auditMove(task, false);
            }
        } catch (RestClientException e) {
            log.warn("device task {} ({} for hu {}) dispatch failed, recording FAILED: {}",
                    task.getId(), task.getCommand(), huOf(task), e.toString());
            task.setStatus("FAILED");
            task.setDetail("adapter call failed: " + e.getMessage());
            auditMove(task, false);
        }
        return DeviceTaskView.from(task);
    }

    /**
     * Apply the terminal result an asynchronous device posts back for a DISPATCHED task. Idempotent:
     * a task that is already terminal (COMPLETED/FAILED) is left unchanged, so a duplicate or late
     * callback can't flip or re-apply it.
     */
    @Transactional
    public DeviceTaskView completeFromCallback(UUID taskId, String status, String detail,
                                               Map<String, Object> resultPayload) {
        DeviceTask task = tasks.findById(taskId)
                .orElseThrow(() -> {
                    log.warn("result callback for unknown device task {} (reported status {}): rejecting with 404",
                            taskId, status);
                    return new DeviceTaskNotFoundException(taskId);
                });
        if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            log.warn("ignoring duplicate result callback for device task {} ({} for hu {}): already {}",
                    taskId, task.getCommand(), huOf(task), task.getStatus());
            return DeviceTaskView.from(task);
        }
        boolean completed = "COMPLETED".equals(status);
        task.setStatus(completed ? "COMPLETED" : "FAILED");
        task.setDetail(detail);
        task.setResult(resultPayload);
        if (completed) {
            log.info("device task {} ({} for hu {}) -> COMPLETED via adapter callback", taskId,
                    task.getCommand(), huOf(task));
        } else {
            log.warn("device task {} ({} for hu {}) -> FAILED via adapter callback: {}", taskId,
                    task.getCommand(), huOf(task), detail);
        }
        auditMove(task, completed);

        // §4 lifecycle wiring: a completing RETRIEVE/CONVEY task advances its linked induction entry.
        // The early-return guard above means this runs exactly once per device task (idempotent).
        advanceInduction(task, completed);
        return DeviceTaskView.from(task);
    }

    /** Branch on the task's command and drive the induction transition keyed off {@code task_id}. */
    private void advanceInduction(DeviceTask task, boolean completed) {
        String command = task.getCommand();
        if ("RETRIEVE".equals(command) || "BIN_RETRIEVE".equals(command)) {
            induction.onRetrieveCompleted(task.getId(), completed, task.getActor());
        } else if ("RELOCATE".equals(command) || "BIN_RELOCATE".equals(command)) {
            // ADR-0009 dig-out: a completing blocker move books + traces the blocker and re-runs
            // the entry's dispatch decision (next relocate, or the real retrieve).
            induction.onRelocateCompleted(task.getId(), completed, task.getPayload(), task.getActor());
        } else if ("CONVEY".equals(command)) {
            // The outbound (induction) and return-to-storage legs share the CONVEY command; each
            // hook looks the entry up by its own task-id column and is a no-op on a miss, so
            // routing the callback to both is safe: at most one of them matches.
            induction.onConveyCompleted(task.getId(), completed, task.getResult(), task.getActor());
            induction.onReturnConveyCompleted(task.getId(), completed, task.getActor());
        } else if ("STORE".equals(command) || "BIN_STORE".equals(command)) {
            induction.onReturnStoreCompleted(task.getId(), completed, task.getActor());
        }
    }

    @Transactional(readOnly = true)
    public DeviceTaskView get(UUID taskId) {
        return DeviceTaskView.from(tasks.findById(taskId)
                .orElseThrow(() -> new DeviceTaskNotFoundException(taskId)));
    }

    @Transactional(readOnly = true)
    public List<DeviceTaskView> byCorrelation(UUID correlationId) {
        return tasks.findByCorrelationIdOrderByCreatedAtAsc(correlationId).stream()
                .map(DeviceTaskView::from)
                .toList();
    }

    /**
     * Recent device tasks (newest first) for the transport overview. Filters are optional and
     * combine; {@code limit} is clamped to [1, 500] so the poll-driven UI can't ask for the world.
     */
    @Transactional(readOnly = true)
    public List<DeviceTaskView> search(UUID warehouseId, String status, String family, UUID equipmentId, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        String statusFilter = blankToNull(status);
        String familyFilter = blankToNull(family);
        return tasks.search(warehouseId, statusFilter, familyFilter, equipmentId, PageRequest.of(0, capped)).stream()
                .map(DeviceTaskView::from)
                .toList();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Append a {@code HandlingUnitMoved} audit event to the txlog system-of-record for a terminal
     * physical move (STORE/RETRIEVE/RELOCATE + AutoStore BIN_* variants); CONVEY transport is skipped
     * (it does not change a stock location and is already on the HU trace). The from/to locations are
     * read from the command's own payload shape. Best-effort: a txlog hiccup must never fail the
     * device-task transition, so failures are caught and logged (mirrors the booking side effects).
     *
     * <p>Called exactly once per task at its terminal transition — synchronously in {@link #request}
     * or from {@link #completeFromCallback}, whose duplicate-callback guard keeps it once-only.
     */
    private void auditMove(DeviceTask task, boolean completed) {
        String command = task.getCommand();
        if (!MOVE_COMMANDS.contains(command)) {
            return;
        }
        Map<String, Object> p = task.getPayload();
        Object fromLocationId;
        Object toLocationId;
        if ("STORE".equals(command) || "BIN_STORE".equals(command)) {
            fromLocationId = null;
            toLocationId = p == null ? null : p.get("locationId");
        } else if ("RETRIEVE".equals(command) || "BIN_RETRIEVE".equals(command)) {
            fromLocationId = p == null ? null : p.get("locationId");
            toLocationId = null;
        } else { // RELOCATE / BIN_RELOCATE
            fromLocationId = p == null ? null : p.get("fromLocationId");
            toLocationId = p == null ? null : p.get("toLocationId");
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", task.getWarehouseId());
        payload.put("huId", p == null ? null : p.get("huId"));
        payload.put("huCode", p == null ? null : p.get("huCode"));
        payload.put("command", command);
        payload.put("family", task.getFamily());
        payload.put("equipmentId", task.getEquipmentId());
        payload.put("fromLocationId", fromLocationId);
        payload.put("toLocationId", toLocationId);
        payload.put("status", completed ? "COMPLETED" : "FAILED");
        payload.put("detail", task.getDetail());
        payload.put("deviceTaskId", task.getId());

        Object huId = payload.get("huId");
        String streamId = huId == null ? "device-task-" + task.getId() : "hu-" + huId;
        try {
            txlog.append(streamId, TxLogClient.HANDLING_UNIT_MOVED, task.getCorrelationId(),
                    task.getActor(), payload);
            log.info("audit: HandlingUnitMoved {} {} for hu {} (task {}, from {} to {})",
                    command, completed ? "COMPLETED" : "FAILED", huOf(task), task.getId(),
                    fromLocationId, toLocationId);
        } catch (RuntimeException e) {
            log.warn("audit: failed to append HandlingUnitMoved for device task {} ({} for hu {}); "
                            + "move stands, audit trail has a gap: {}",
                    task.getId(), command, huOf(task), e.toString());
        }
    }

    /**
     * The human-readable HU identifier carried in the task payload for log lines: {@code huCode}
     * when present (device-task payloads usually carry it), else {@code huId}, else "-".
     */
    private static String huOf(DeviceTask task) {
        Map<String, Object> payload = task.getPayload();
        Object code = payload == null ? null : payload.get("huCode");
        if (code == null && payload != null) {
            code = payload.get("huId");
        }
        return code == null ? "-" : code.toString();
    }
}
