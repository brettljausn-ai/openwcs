package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.api.NotFoundException;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.domain.CountLine;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle-counting workflow: generate a task (snapshot expected qty from inventory), capture counts
 * (blind vs variance), and reconcile each line against tolerance — auto-approve + post a
 * {@code StockAdjusted} adjustment, or flag a recount (which spawns a follow-up task). Every
 * mutation records the acting identity (forwarded {@code X-Auth-User}).
 */
@Service
public class CountingService {

    private static final Logger log = LoggerFactory.getLogger(CountingService.class);

    private final CountTaskRepository tasks;
    private final CountLineRepository lines;
    private final InventoryClient inventory;
    private final TxLogClient txlog;
    private final CountRoutingService routing;

    public CountingService(CountTaskRepository tasks, CountLineRepository lines,
                           InventoryClient inventory, TxLogClient txlog, CountRoutingService routing) {
        this.tasks = tasks;
        this.lines = lines;
        this.inventory = inventory;
        this.txlog = txlog;
        this.routing = routing;
    }

    /**
     * Create a count task and snapshot each cell's expected on-hand from inventory into a count line.
     * The task opens in OPEN; lines open in PENDING.
     */
    @Transactional
    public CountTask generate(CreateCountTaskCommand cmd) {
        if (cmd.warehouseId() == null) {
            throw new IllegalArgumentException("warehouseId is required");
        }
        if (cmd.cells() == null || cmd.cells().isEmpty()) {
            throw new IllegalArgumentException("a count task needs at least one (location, SKU) cell");
        }

        CountTask task = new CountTask();
        task.setWarehouseId(cmd.warehouseId());
        task.setScopeType(cmd.scopeType() == null ? "LOCATION" : cmd.scopeType());
        task.setScopeRef(cmd.scopeRef());
        task.setCountType(cmd.countType() == null ? "BLIND" : cmd.countType());
        task.setOrigin(cmd.origin() == null ? "AD_HOC" : cmd.origin());
        task.setScheduleId(cmd.scheduleId());
        task.setParentTaskId(cmd.parentTaskId());
        task.setTolerance(cmd.tolerance() == null ? BigDecimal.ZERO : cmd.tolerance());
        task.setGtpStationId(cmd.gtpStationId());
        task.setStatus("OPEN");
        CountTask saved = tasks.save(task);

        for (CountTaskScope cell : cmd.cells()) {
            CountLine line = new CountLine();
            line.setCountTaskId(saved.getId());
            line.setWarehouseId(cmd.warehouseId());
            line.setLocationId(cell.locationId());
            line.setSkuId(cell.skuId());
            line.setBatchId(cell.batchId());
            line.setUomCode(cell.uomCode());
            // Expected snapshot from the inventory availability projection (the read seam).
            line.setExpectedQty(inventory.expectedOnHand(cmd.warehouseId(), cell.skuId(), cell.locationId()));
            line.setStatus("PENDING");
            lines.save(line);
        }

        // Best-effort: when the emulator is on, route any ASRS-stored count totes to a GTP counting
        // station. A routing problem must never break count-task creation, so swallow everything.
        try {
            routing.routeAsrsCells(cmd.warehouseId(), cmd.cells());
        } catch (Throwable t) {
            log.warn("ASRS count-tote routing failed for task {} (count task still created): {}",
                    saved.getId(), t.toString());
        }
        return saved;
    }

    /** Claim an OPEN task for an operator. */
    @Transactional
    public CountTask claim(UUID taskId, String operator) {
        CountTask task = task(taskId);
        if (!"OPEN".equals(task.getStatus())) {
            throw new IllegalStateException("task " + taskId + " is not OPEN (status=" + task.getStatus() + ")");
        }
        task.setAssignedTo(operator);
        return task;
    }

    /**
     * Record the operator's counted quantities. BLIND vs VARIANCE only affects what the operator
     * was shown (see {@link #linesFor}); on submit the variance (counted − expected) is computed
     * server-side either way. Moves the task to COUNTED.
     */
    @Transactional
    public CountTask submitCounts(UUID taskId, List<CountEntry> entries, String operator) {
        CountTask task = task(taskId);
        if ("RECONCILED".equals(task.getStatus())) {
            throw new IllegalStateException("task " + taskId + " is already reconciled");
        }
        List<CountLine> taskLines = lines.findByCountTaskId(taskId);
        for (CountEntry entry : entries) {
            CountLine line = taskLines.stream()
                    .filter(l -> l.getId().equals(entry.countLineId()))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("count line not found: " + entry.countLineId()));
            if (entry.countedQty() == null) {
                throw new IllegalArgumentException("countedQty is required for line " + entry.countLineId());
            }
            line.setCountedQty(entry.countedQty());
            line.setVariance(entry.countedQty().subtract(line.getExpectedQty()));
            line.setStatus("COUNTED");
        }
        task.setCountedBy(operator);
        task.setCountedAt(Instant.now());
        task.setStatus("COUNTED");
        return task;
    }

    /**
     * Reconcile a COUNTED task. For each counted line: within tolerance (|variance| ≤ tolerance)
     * auto-approves and, when there is a non-zero variance, posts a {@code StockAdjusted} adjustment
     * (the inventory path) and marks the line ADJUSTED; out-of-tolerance flags the line RECOUNT.
     * If any line needs a recount the task goes to RECOUNT and a follow-up task is spawned for just
     * those cells; otherwise the task is RECONCILED.
     */
    @Transactional
    public ReconciliationResult reconcile(UUID taskId, String actor) {
        CountTask task = task(taskId);
        if (!"COUNTED".equals(task.getStatus()) && !"RECOUNT".equals(task.getStatus())) {
            throw new IllegalStateException(
                    "task " + taskId + " must be COUNTED to reconcile (status=" + task.getStatus() + ")");
        }
        BigDecimal tolerance = task.getTolerance() == null ? BigDecimal.ZERO : task.getTolerance().abs();

        List<CountLine> taskLines = lines.findByCountTaskId(taskId);
        List<CountTaskScope> recountCells = new ArrayList<>();
        int approved = 0;
        int adjusted = 0;
        int recounts = 0;

        for (CountLine line : taskLines) {
            if (line.getCountedQty() == null) {
                continue; // not counted; leave pending
            }
            BigDecimal variance = line.getVariance() == null ? BigDecimal.ZERO : line.getVariance();
            if (variance.abs().compareTo(tolerance) <= 0) {
                approved++;
                if (variance.signum() != 0) {
                    UUID eventId = txlog.postStockAdjusted(new TxLogClient.StockAdjustment(
                            line.getWarehouseId(), line.getSkuId(), line.getBatchId(), line.getLocationId(),
                            variance, line.getUomCode(), task.getId(), line.getId(), actor));
                    line.setAdjustmentEventId(eventId);
                    line.setStatus("ADJUSTED");
                    adjusted++;
                } else {
                    line.setStatus("APPROVED");
                }
            } else {
                line.setStatus("RECOUNT");
                recounts++;
                recountCells.add(new CountTaskScope(
                        line.getLocationId(), line.getSkuId(), line.getBatchId(), line.getUomCode()));
            }
        }

        task.setReconciledBy(actor);
        task.setReconciledAt(Instant.now());

        UUID recountTaskId = null;
        if (!recountCells.isEmpty()) {
            task.setStatus("RECOUNT");
            CountTask recount = generate(new CreateCountTaskCommand(
                    task.getWarehouseId(), task.getScopeType(), task.getScopeRef(),
                    task.getCountType(), "RECOUNT", task.getScheduleId(), task.getId(),
                    task.getTolerance(), task.getGtpStationId(), recountCells));
            recountTaskId = recount.getId();
            log.info("count task {} flagged {} line(s) for recount -> task {}", taskId, recounts, recountTaskId);
        } else {
            task.setStatus("RECONCILED");
        }
        return new ReconciliationResult(task.getId(), task.getStatus(), approved, adjusted, recounts, recountTaskId);
    }

    /**
     * The lines an operator sees while counting. For a BLIND count the expected qty is withheld
     * (returned as {@code null}) so the operator counts without anchoring; VARIANCE shows it.
     */
    @Transactional(readOnly = true)
    public List<CountLineView> linesFor(UUID taskId) {
        CountTask task = task(taskId);
        boolean blind = "BLIND".equals(task.getCountType());
        return lines.findByCountTaskId(taskId).stream()
                .map(l -> CountLineView.of(l, blind))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CountLine> rawLines(UUID taskId) {
        task(taskId); // 404 if missing
        return lines.findByCountTaskId(taskId);
    }

    public CountTask task(UUID taskId) {
        return tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("count task not found: " + taskId));
    }

    /**
     * Delete a count task that has not started yet. Only OPEN tasks can be removed (once counting has
     * begun the task carries operational history); its lines are removed with it.
     */
    @Transactional
    public void deleteTask(UUID taskId) {
        CountTask task = task(taskId);
        if (!"OPEN".equals(task.getStatus())) {
            throw new IllegalStateException(
                    "Only OPEN count tasks can be deleted; this one is " + task.getStatus() + ".");
        }
        lines.deleteAll(lines.findByCountTaskId(taskId));
        tasks.delete(task);
    }
}
