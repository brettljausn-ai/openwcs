package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.InductionEntryNotFoundException;
import org.openwcs.flow.api.InductionEntryView;
import org.openwcs.flow.api.InductionRequest;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.client.WorkplaceClient;
import org.openwcs.flow.domain.InductionQueueEntry;
import org.openwcs.flow.repo.InductionQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the inbound induction / presentation queue (ADR-0007 §3c-1): the
 * {@code REQUESTED → IN_TRANSIT → QUEUED → DONE} pipeline of totes being delivered to a workplace.
 *
 * <p>A request creates a {@code REQUESTED} entry (uncapped) and flow then meters retrievals into the
 * {@code {IN_TRANSIT, QUEUED}} cap by dispatching RETRIEVE device tasks. The RETRIEVE callback
 * advances the entry to {@code IN_TRANSIT} and dispatches the CONVEY leg; the CONVEY (= arrival)
 * callback advances it to {@code QUEUED} and assigns an arrival sequence (arrival order, R2/R4); the
 * operator completes it to {@code DONE}. Each transition appends an {@link HuTraceService} row (§5).
 *
 * <p>Lifecycle transitions are driven from {@code DeviceTaskService.completeFromCallback} keyed off
 * the completing task's command; the entry is found by its {@code retrieve_task_id} / {@code convey_task_id}.
 */
@Service
public class InductionQueueService {

    private static final Logger log = LoggerFactory.getLogger(InductionQueueService.class);

    private static final List<String> CAP_STATUSES = List.of("IN_TRANSIT", "QUEUED");
    /** Mode classes for the cap split (gtp maxInTransitPicking vs maxInTransitOther). */
    private static final List<String> PICKING_MODES = List.of("PICKING");

    private final InductionQueueEntryRepository entries;
    private final HuTraceService trace;
    private final DeviceTaskService deviceTasks;
    private final WorkplaceClient workplaces;

    public InductionQueueService(InductionQueueEntryRepository entries, HuTraceService trace,
                                 DeviceTaskService deviceTasks, WorkplaceClient workplaces) {
        this.entries = entries;
        this.trace = trace;
        this.deviceTasks = deviceTasks;
        this.workplaces = workplaces;
    }

    // ---- §3.1 request -------------------------------------------------------------------------

    /**
     * Create a {@code REQUESTED} entry (always succeeds — REQUESTED is uncapped), write a
     * {@code REQUESTED} trace row, then meter retrievals into the cap.
     */
    @Transactional
    public InductionEntryView request(InductionRequest req, String actor) {
        InductionQueueEntry entry = new InductionQueueEntry();
        entry.setWarehouseId(req.warehouseId());
        entry.setWorkplaceId(req.workplaceId());
        entry.setWorkplaceKind(req.workplaceKind() == null ? "GTP_STATION" : req.workplaceKind());
        entry.setHuId(req.huId());
        entry.setHuCode(req.huCode());
        entry.setSkuId(req.skuId());
        entry.setSkuCode(req.skuCode());
        entry.setQty(req.qty());
        entry.setLocationId(req.locationId());
        entry.setMode(req.mode());
        entry.setCountTaskId(req.countTaskId());
        entry.setCountLineId(req.countLineId());
        entry.setStatus("REQUESTED");
        entries.saveAndFlush(entry);

        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "request", "REQUESTED",
                null, null, null, entry.getWorkplaceId(), null, entry.getId());

        meterRetrievals(entry.getWorkplaceId(), req.family(), actor);
        // Re-read so the response reflects any RETRIEVE dispatched for this entry by the meter pass.
        return InductionEntryView.from(entries.findById(entry.getId()).orElse(entry));
    }

    // ---- §4.1 cap metering --------------------------------------------------------------------

    /**
     * Promote {@code REQUESTED} backlog into transit while the {@code {IN_TRANSIT, QUEUED}} cap has
     * room: for each oldest {@code REQUESTED} entry without a retrieve task, dispatch its RETRIEVE
     * and stamp {@code retrieve_task_id}. An entry that already has a retrieve task in flight counts
     * against the cap, so we never over-dispatch beyond the workplace's capacity.
     */
    @Transactional
    public void meterRetrievals(UUID workplaceId, String family, String actor) {
        WorkplaceClient.Caps caps = workplaces.caps(workplaceId);
        List<InductionQueueEntry> backlog =
                entries.findByWorkplaceIdAndStatusOrderByRequestedAtAsc(workplaceId, "REQUESTED");
        for (InductionQueueEntry entry : backlog) {
            if (entry.getRetrieveTaskId() != null) {
                continue; // already retrieving — it occupies a cap slot but needs no new dispatch
            }
            int cap = isPicking(entry.getMode()) ? caps.picking() : caps.other();
            if (capUsage(workplaceId, entry.getMode()) >= cap) {
                continue; // cap full for this mode class: leave it REQUESTED (uncapped backlog)
            }
            dispatchRetrieve(entry, family == null ? defaultFamily(entry) : family, actor);
        }
    }

    /**
     * Cap usage for a workplace + mode class: {@code {IN_TRANSIT, QUEUED}} entries plus any
     * {@code REQUESTED} entries already committed to retrieval (retrieve task dispatched). Both
     * mode-classed so PICKING and other modes meter against their own cap.
     */
    private long capUsage(UUID workplaceId, String mode) {
        long picking = entries.countByWorkplaceIdAndModeInAndStatusIn(workplaceId, PICKING_MODES, CAP_STATUSES);
        long inTransitOrQueued = isPicking(mode)
                ? picking
                : entries.countByWorkplaceIdAndStatusIn(workplaceId, CAP_STATUSES) - picking;
        return inTransitOrQueued + countCommittedRequested(workplaceId, mode);
    }

    /** REQUESTED entries that already have a retrieve task in flight, in the same mode class. */
    private long countCommittedRequested(UUID workplaceId, String mode) {
        return entries.findByWorkplaceIdAndStatusOrderByRequestedAtAsc(workplaceId, "REQUESTED").stream()
                .filter(e -> e.getRetrieveTaskId() != null)
                .filter(e -> isPicking(mode) == isPicking(e.getMode()))
                .count();
    }

    private void dispatchRetrieve(InductionQueueEntry entry, String family, String actor) {
        String command = "AUTOSTORE".equalsIgnoreCase(family) ? "BIN_RETRIEVE" : "RETRIEVE";
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "locationId", entry.getLocationId());
        putIfPresent(payload, "destinationWorkplaceId", entry.getWorkplaceId());
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), family, null, command, payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setRetrieveTaskId(taskId);
        log.debug("Induction entry {} dispatched RETRIEVE task {} ({}/{})",
                entry.getId(), taskId, family, command);
    }

    // ---- §4.2 RETRIEVE callback ---------------------------------------------------------------

    /**
     * RETRIEVE/BIN_RETRIEVE device task COMPLETED: advance the linked entry {@code REQUESTED →
     * IN_TRANSIT}, trace {@code RETRIEVED} + {@code INDUCTED}, and dispatch the CONVEY leg.
     * Idempotent: only advances from {@code REQUESTED}; a late/duplicate callback is a no-op.
     */
    @Transactional
    public void onRetrieveCompleted(UUID retrieveTaskId, boolean succeeded, String actor) {
        InductionQueueEntry entry = entries.findByRetrieveTaskId(retrieveTaskId).orElse(null);
        if (entry == null) {
            return; // RETRIEVE not tied to an induction entry (e.g. a store-back / other flow)
        }
        if (!succeeded) {
            // Leave REQUESTED so a retry can re-meter; drop the failed task link so it re-dispatches.
            log.debug("RETRIEVE {} FAILED for induction entry {}; leaving REQUESTED", retrieveTaskId, entry.getId());
            entry.setRetrieveTaskId(null);
            return;
        }
        if (!"REQUESTED".equals(entry.getStatus())) {
            return; // already advanced — idempotent
        }
        entry.setStatus("IN_TRANSIT");
        entry.setInTransitAt(java.time.Instant.now());

        String slot = entry.getLocationId() == null ? "slot" : "slot:" + entry.getLocationId();
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), slot, "RETRIEVED",
                "retrieved from source slot", null, "conveyor", entry.getWorkplaceId(), retrieveTaskId,
                entry.getId());
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "conveyor", "INDUCTED",
                "inducted onto conveyor", null, null, entry.getWorkplaceId(), retrieveTaskId, entry.getId());

        dispatchConvey(entry, actor);
    }

    private void dispatchConvey(InductionQueueEntry entry, String actor) {
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "destinationWorkplaceId", entry.getWorkplaceId());
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), "CONVEYOR", null, "CONVEY", payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setConveyTaskId(taskId);
        log.debug("Induction entry {} dispatched CONVEY task {}", entry.getId(), taskId);
    }

    // ---- §4.3 CONVEY callback (= arrival) -----------------------------------------------------

    /**
     * CONVEY device task COMPLETED (= arrival at the induction point): advance the linked entry
     * {@code IN_TRANSIT → QUEUED}, assign the next per-workplace arrival sequence (arrival order),
     * and trace {@code ARRIVED} + {@code QUEUED}. Idempotent: only advances from {@code IN_TRANSIT}.
     */
    @Transactional
    public void onConveyCompleted(UUID conveyTaskId, boolean succeeded, Map<String, Object> result, String actor) {
        InductionQueueEntry entry = entries.findByConveyTaskId(conveyTaskId).orElse(null);
        if (entry == null) {
            return;
        }
        if (!succeeded) {
            log.debug("CONVEY {} FAILED for induction entry {}; leaving IN_TRANSIT", conveyTaskId, entry.getId());
            return;
        }
        if (!"IN_TRANSIT".equals(entry.getStatus())) {
            return; // already QUEUED/DONE — idempotent
        }
        long seq = nextArrivalSeq(entry.getWorkplaceId());
        entry.setStatus("QUEUED");
        entry.setQueuedAt(java.time.Instant.now());
        entry.setArrivalSeq(seq);

        // R4: the emulator reports the conveyor decision points (divert / recirculate) it passed; trace
        // them before ARRIVED so the timeline explains why arrival diverged from request order.
        recordConveyDecisions(entry, conveyTaskId, result);
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "station:" + entry.getWorkplaceId(),
                "ARRIVED", "arrived at induction point", "conveyor", null, entry.getWorkplaceId(),
                conveyTaskId, entry.getId());
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "station:" + entry.getWorkplaceId(),
                "QUEUED", "queued at arrival_seq " + seq, null, null, entry.getWorkplaceId(),
                conveyTaskId, entry.getId());
    }

    /**
     * Write the conveyor decision points (divert / recirculate) the emulator reported in the CONVEY
     * result payload to the HU transport trace. Defensive against a missing or malformed payload.
     */
    private void recordConveyDecisions(InductionQueueEntry entry, UUID conveyTaskId, Map<String, Object> result) {
        if (result == null || !(result.get("decisions") instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            // m is Map<?,?> (wildcard), so getOrDefault's typed default won't compile — use get() + a
            // null fallback instead.
            Object pointObj = m.get("point");
            Object eventObj = m.get("event");
            String point = pointObj == null ? "sorter" : pointObj.toString();
            String event = eventObj == null ? "DECISION" : eventObj.toString();
            Object decision = m.get("decision");
            trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), point,
                    event, decision == null ? null : decision.toString(), "conveyor", null,
                    entry.getWorkplaceId(), conveyTaskId, entry.getId());
        }
    }

    private long nextArrivalSeq(UUID workplaceId) {
        Long max = entries.maxArrivalSeq(workplaceId);
        return (max == null ? 0L : max) + 1L;
    }

    // ---- §3.3 DONE ----------------------------------------------------------------------------

    /**
     * Mark an entry DONE (operator-driven). Idempotent: an already-DONE entry is returned unchanged.
     * Frees a cap slot, so the workplace's REQUESTED backlog is re-metered. Flow does NOT store back.
     */
    @Transactional
    public InductionEntryView markDone(UUID entryId, String family, String actor) {
        InductionQueueEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new InductionEntryNotFoundException(entryId));
        if ("DONE".equals(entry.getStatus())) {
            return InductionEntryView.from(entry);
        }
        entry.setStatus("DONE");
        entry.setDoneAt(java.time.Instant.now());
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "station:" + entry.getWorkplaceId(),
                "DONE", "presentation completed", null, null, entry.getWorkplaceId(), null, entry.getId());

        // A slot freed: re-meter the backlog for this workplace.
        meterRetrievals(entry.getWorkplaceId(), family, actor);
        return InductionEntryView.from(entry);
    }

    // ---- §3.2 read ----------------------------------------------------------------------------

    /**
     * The workplace's inbound pipeline {@code {REQUESTED, IN_TRANSIT, QUEUED}} (DONE excluded),
     * QUEUED first in arrival_seq ASC, then IN_TRANSIT, then REQUESTED. With {@code status} only
     * that single status (e.g. the workable QUEUED head) is returned.
     */
    @Transactional(readOnly = true)
    public List<InductionEntryView> readQueue(UUID workplaceId, String status) {
        List<InductionQueueEntry> rows = (status == null || status.isBlank())
                ? entries.findActiveSlice(workplaceId)
                : entries.findSliceByStatus(workplaceId, status);
        return rows.stream().map(InductionEntryView::from).toList();
    }

    @Transactional(readOnly = true)
    public InductionEntryView get(UUID entryId) {
        return InductionEntryView.from(entries.findById(entryId)
                .orElseThrow(() -> new InductionEntryNotFoundException(entryId)));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static boolean isPicking(String mode) {
        return "PICKING".equalsIgnoreCase(mode);
    }

    private static String defaultFamily(InductionQueueEntry entry) {
        return "ASRS";
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
