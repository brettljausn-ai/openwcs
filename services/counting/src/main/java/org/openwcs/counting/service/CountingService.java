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

    /** Reason code stamped on every counting-driven stock adjustment (the host webhook carries it). */
    static final String REASON_COUNTING = "COUNTING";

    private final CountTaskRepository tasks;
    private final CountLineRepository lines;
    private final InventoryClient inventory;
    private final TxLogClient txlog;

    public CountingService(CountTaskRepository tasks, CountLineRepository lines,
                           InventoryClient inventory, TxLogClient txlog) {
        this.tasks = tasks;
        this.lines = lines;
        this.inventory = inventory;
        this.txlog = txlog;
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
            // Snapshot the tote the cell sits on (ASRS-family stock lives on an HU; bin stock has none),
            // so a reconciled variance adjusts the tote's bucket instead of a phantom HU-less one.
            line.setHuId(inventory.findHuAt(cmd.warehouseId(), cell.skuId(), cell.locationId())
                    .map(InventoryClient.HandlingUnit::huId).orElse(null));
            line.setStatus("PENDING");
            lines.save(line);
        }

        // Routing runs out-of-band: the task is created with routing_status PENDING (the column
        // default) and the background CountRoutingScheduler picks it up within a minute. We do not
        // route inside this transaction because routing does HTTP (slow, and must not block creation).
        log.info("count task {} created: scope {} {}, type {}, origin {}, {} line(s), tolerance {}{}",
                saved.getId(), saved.getScopeType(), saved.getScopeRef(), saved.getCountType(),
                saved.getOrigin(), cmd.cells().size(), saved.getTolerance(),
                saved.getParentTaskId() != null ? ", recount of task " + saved.getParentTaskId() : "");
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
        log.info("count task {} claimed by operator {}", taskId, operator);
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
        log.info("count task {} counted by operator {}: {} line(s) recorded ({} count) -> COUNTED",
                taskId, operator, entries.size(), task.getCountType());
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
                            line.getHuId(), variance, line.getUomCode(), task.getId(), line.getId(), actor,
                            REASON_COUNTING));
                    line.setAdjustmentEventId(eventId);
                    line.setStatus("ADJUSTED");
                    adjusted++;
                    log.info("count task {} line {}: variance {} within tolerance {}, posted StockAdjusted "
                                    + "delta {} (reason COUNTING, sku {}, location {}, hu {}, actor {}) -> event {}",
                            taskId, line.getId(), variance, tolerance, variance, line.getSkuId(),
                            line.getLocationId(), line.getHuId(), actor, eventId);
                } else {
                    line.setStatus("APPROVED");
                    log.debug("count task {} line {}: counted matches expected (sku {}, location {}), approved",
                            taskId, line.getId(), line.getSkuId(), line.getLocationId());
                }
            } else {
                line.setStatus("RECOUNT");
                recounts++;
                recountCells.add(new CountTaskScope(
                        line.getLocationId(), line.getSkuId(), line.getBatchId(), line.getUomCode()));
                log.info("count task {} line {}: variance {} exceeds tolerance {} (sku {}, location {}), "
                                + "flagged for recount; no adjustment posted",
                        taskId, line.getId(), variance, tolerance, line.getSkuId(), line.getLocationId());
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
            log.info("count task {} reconciled by {} with {} out-of-tolerance line(s): task stays alive "
                            + "as RECOUNT, follow-up task {} spawned for those cells",
                    taskId, actor, recounts, recountTaskId);
        } else {
            task.setStatus("RECONCILED");
            log.info("count task {} reconciled by {}: {} line(s) approved, {} adjusted, no recounts",
                    taskId, actor, approved, adjusted);
        }
        return new ReconciliationResult(task.getId(), task.getStatus(), approved, adjusted, recounts, recountTaskId);
    }

    /**
     * Record a single at-station blind count for one line. The operator works the tote in front of
     * them and never sees the system quantity or their previous count, so the state machine drives a
     * recount until two counts agree:
     *
     * <ul>
     *   <li>First count == system qty: ACCEPTED, no adjustment.</li>
     *   <li>First count != system qty: hold it and ask for a recount.</li>
     *   <li>Recount == system qty: ACCEPTED, no adjustment (the first count was a miscount).</li>
     *   <li>Recount == the held count (two counts agree and differ from the system qty): post a
     *       host-visible stock adjustment (delta = counted - expected, reason COUNTING) and mark the
     *       line ADJUSTED.</li>
     *   <li>Recount != the held count: hold the new count and ask for a third count.</li>
     * </ul>
     *
     * When the line lands terminal (ACCEPTED/ADJUSTED) and all the task's lines are terminal, the task
     * goes RECONCILED.
     */
    @Transactional
    public StationCountResult recordStationCount(UUID taskId, UUID lineId, BigDecimal countedQty, String actor) {
        if (countedQty == null) {
            throw new IllegalArgumentException("countedQty is required");
        }
        task(taskId); // 404 if the task does not exist
        CountLine line = lines.findByCountTaskId(taskId).stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("count line not found on task " + taskId + ": " + lineId));

        BigDecimal expected = line.getExpectedQty() == null ? BigDecimal.ZERO : line.getExpectedQty();
        String state = line.getStationCountState();

        // Already finished: never re-process a terminal line (guards against a double stock adjustment
        // to the host if the endpoint is called again after the tote was completed).
        if ("ADJUSTED".equals(state)) {
            return new StationCountResult("ADJUSTED", "This tote was already counted and adjusted.");
        }
        if ("ACCEPTED".equals(state)) {
            return new StationCountResult("ACCEPTED", "This tote was already counted.");
        }

        boolean firstCount = !"RECOUNT".equals(state) || line.getStationLastCount() == null;

        if (firstCount) {
            if (countedQty.compareTo(expected) == 0) {
                return accept(line, countedQty);
            }
            line.setStationLastCount(countedQty);
            line.setStationCountState("RECOUNT");
            // blind count: the expected qty is deliberately not logged while the line is open.
            log.info("station count on task {} line {}: first count {} differs from the system qty, "
                    + "recount requested (blind)", taskId, lineId, countedQty);
            return new StationCountResult("RECOUNT", "Recount this tote.");
        }

        // RECOUNT: we are comparing this count against the held one.
        if (countedQty.compareTo(expected) == 0) {
            return accept(line, countedQty);
        }
        if (countedQty.compareTo(line.getStationLastCount()) == 0) {
            // Two counts agree and they differ from the system qty -> confirmed variance, adjust.
            BigDecimal delta = countedQty.subtract(expected);
            UUID eventId = txlog.postStockAdjusted(new TxLogClient.StockAdjustment(
                    line.getWarehouseId(), line.getSkuId(), line.getBatchId(), line.getLocationId(),
                    line.getHuId(), delta, line.getUomCode(), taskId, line.getId(),
                    actor == null || actor.isBlank() ? "system" : actor, REASON_COUNTING));
            line.setCountedQty(countedQty);
            line.setVariance(delta);
            line.setAdjustmentEventId(eventId);
            line.setStatus("ADJUSTED");
            line.setStationCountState("ADJUSTED");
            log.info("station count on task {} line {}: two counts agree at {}, confirmed variance; "
                            + "posted StockAdjusted delta {} (reason COUNTING, sku {}, location {}, hu {}, "
                            + "actor {}) -> event {}",
                    taskId, lineId, countedQty, delta, line.getSkuId(), line.getLocationId(),
                    line.getHuId(), actor == null || actor.isBlank() ? "system" : actor, eventId);
            reconcileIfAllTerminal(taskId);
            return new StationCountResult("ADJUSTED", "Adjusted by " + delta.toPlainString() + "; sent to the host.");
        }
        // This count differs from the last one: hold it and count a third time.
        line.setStationLastCount(countedQty);
        line.setStationCountState("RECOUNT");
        log.info("station count on task {} line {}: count {} differs from the held count, "
                + "a third count is required (blind)", taskId, lineId, countedQty);
        return new StationCountResult("RECOUNT", "Counts did not match. Count again.");
    }

    /** Accept a line at the system qty (no adjustment) and reconcile the task if it is now complete. */
    private StationCountResult accept(CountLine line, BigDecimal countedQty) {
        line.setCountedQty(countedQty);
        line.setVariance(BigDecimal.ZERO);
        line.setStatus("APPROVED");
        line.setStationCountState("ACCEPTED");
        log.info("station count on task {} line {}: count {} matches the system qty (sku {}, hu {}), "
                        + "accepted with no adjustment",
                line.getCountTaskId(), line.getId(), countedQty, line.getSkuId(), line.getHuId());
        reconcileIfAllTerminal(line.getCountTaskId());
        return new StationCountResult("ACCEPTED", "Count matches the system quantity.");
    }

    /** Once every line on a task is terminal (APPROVED/ADJUSTED), mark the task RECONCILED. */
    private void reconcileIfAllTerminal(UUID taskId) {
        List<CountLine> taskLines = lines.findByCountTaskId(taskId);
        boolean allTerminal = taskLines.stream()
                .allMatch(l -> "APPROVED".equals(l.getStatus()) || "ADJUSTED".equals(l.getStatus()));
        if (allTerminal) {
            CountTask task = task(taskId);
            task.setStatus("RECONCILED");
            log.info("count task {} reconciled: all {} line(s) reached a terminal state at the station",
                    taskId, taskLines.size());
        }
    }

    /** Outcome of an at-station blind count: outcome is ACCEPTED | RECOUNT | ADJUSTED. */
    public record StationCountResult(String outcome, String message) {
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
        log.info("count task {} deleted before counting started (scope {} {}, origin {})",
                taskId, task.getScopeType(), task.getScopeRef(), task.getOrigin());
    }
}
