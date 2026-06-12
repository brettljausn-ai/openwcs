package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.api.InductionEntryNotFoundException;
import org.openwcs.flow.api.InductionEntryView;
import org.openwcs.flow.api.InductionRequest;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.client.InventoryClient;
import org.openwcs.flow.client.SlottingClient;
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
 * operator completes it to {@code DONE}. Completion starts the return-to-storage leg: a return
 * CONVEY (station → storage) whose callback dispatches the STORE/BIN_STORE back into the source
 * slot. Each transition appends an {@link HuTraceService} row (§5).
 *
 * <p>ADR-0008 §1: both CONVEY dispatches (outbound and return) resolve the transport's entry and
 * destination nodes on the projected routing graph via {@link TransportNodeResolver}; when both
 * resolve, a route plan is assigned ({@link RoutingService#assignRoute}) and the payload carries
 * {@code entryNode}/{@code destinationNode} so the adapter runs the leg as a live scan-driven walk.
 * Un-projected warehouses dispatch exactly as before (atomic fallback).
 *
 * <p>ADR-0009: before dispatching a RETRIEVE, flow asks slotting for the channel's relocation plan.
 * While a blocker sits in front of the tote (multi-deep channel), a RELOCATE device task moves the
 * front-most blocker instead; its callback books the blocker's new location, traces {@code RELOCATED}
 * and re-runs the dispatch decision until the channel is clear and the real RETRIEVE goes out.
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
    private final TransportNodeResolver transportNodes;
    private final RoutingService routing;
    private final InventoryClient inventory;
    private final SlottingClient slotting;

    public InductionQueueService(InductionQueueEntryRepository entries, HuTraceService trace,
                                 DeviceTaskService deviceTasks, WorkplaceClient workplaces,
                                 TransportNodeResolver transportNodes, RoutingService routing,
                                 InventoryClient inventory, SlottingClient slotting) {
        this.entries = entries;
        this.trace = trace;
        this.deviceTasks = deviceTasks;
        this.workplaces = workplaces;
        this.transportNodes = transportNodes;
        this.routing = routing;
        this.inventory = inventory;
        this.slotting = slotting;
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
        log.info("induction entry {} REQUESTED: hu {} (sku {}, qty {}) to workplace {} (mode {}, actor {})",
                entry.getId(), entry.getHuCode(), entry.getSkuCode(), entry.getQty(), entry.getWorkplaceId(),
                entry.getMode(), actor);

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
            if (isCommitted(entry)) {
                continue; // already retrieving (or mid-dig-out) — occupies a cap slot, no new dispatch
            }
            int cap = isPicking(entry.getMode()) ? caps.picking() : caps.other();
            long usage = capUsage(workplaceId, entry.getMode());
            if (usage >= cap) {
                // Cap full for this mode class: leave it REQUESTED (uncapped backlog).
                log.warn("retrieve deferred for hu {} (induction entry {}): in-transit cap {} reached "
                                + "at workplace {} ({} in flight, mode class {}); entry stays REQUESTED "
                                + "until a slot frees", entry.getHuCode(), entry.getId(), cap,
                        workplaceId, usage, isPicking(entry.getMode()) ? "picking" : "other");
                continue;
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

    /** REQUESTED entries already committed to retrieval, in the same mode class. */
    private long countCommittedRequested(UUID workplaceId, String mode) {
        return entries.findByWorkplaceIdAndStatusOrderByRequestedAtAsc(workplaceId, "REQUESTED").stream()
                .filter(InductionQueueService::isCommitted)
                .filter(e -> isPicking(mode) == isPicking(e.getMode()))
                .count();
    }

    /**
     * Whether a REQUESTED entry is already committed to retrieval: a RETRIEVE in flight, or — ADR-0009
     * — a RELOCATE digging its channel out (the chain toward retrieving this HU has started, so it
     * occupies its cap slot from the first relocate).
     */
    private static boolean isCommitted(InductionQueueEntry entry) {
        return entry.getRetrieveTaskId() != null || entry.getRelocateTaskId() != null;
    }

    private void dispatchRetrieve(InductionQueueEntry entry, String family, String actor) {
        // ADR-0009: a multi-deep channel may need a dig-out first. While slotting reports a blocker
        // in front of the tote, the front-most blocker is RELOCATEd instead of retrieving; the
        // relocate callback re-runs this decision until the channel is clear.
        if (entry.getLocationId() != null && dispatchRelocate(entry, actor)) {
            return;
        }
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
        log.info("induction entry {}: dispatched {} task {} for hu {} from location {} ({} family, "
                        + "cap slot taken at workplace {})", entry.getId(), command, taskId,
                entry.getHuCode(), entry.getLocationId(), family, entry.getWorkplaceId());
    }

    // ---- ADR-0009 dig-out: RELOCATE chain before a blocked retrieve ----------------------------

    /**
     * Ask slotting for the channel's relocation plan and, when a blocker must move first, dispatch
     * a RELOCATE/BIN_RELOCATE for the front-most step (stamping {@code relocate_task_id}) instead of
     * the RETRIEVE. Returns {@code false} — caller dispatches the RETRIEVE as today — when the
     * channel is clear, the plan is unplannable ({@code blocked=true}, degraded: the emulator does
     * not enforce blocking) or the slotting call failed (isolated: the chain must degrade to a
     * direct retrieve, never block the pipeline).
     */
    private boolean dispatchRelocate(InductionQueueEntry entry, String actor) {
        SlottingClient.RelocationPlan plan;
        try {
            plan = slotting.plan(entry.getWarehouseId(), entry.getLocationId());
        } catch (RuntimeException e) {
            log.warn("relocation-plan lookup failed for hu {} (induction entry {}, location {}); "
                    + "dispatching the RETRIEVE degraded: {}", entry.getHuCode(), entry.getId(),
                    entry.getLocationId(), e.toString());
            return false;
        }
        if (plan == null || plan.steps() == null || plan.steps().isEmpty()) {
            if (plan != null && plan.blocked()) {
                log.warn("channel of hu {} (induction entry {}, location {}) is blocked but unplannable; "
                        + "dispatching the RETRIEVE degraded", entry.getHuCode(), entry.getId(),
                        entry.getLocationId());
            }
            return false; // channel clear (or degraded): retrieve directly
        }
        SlottingClient.RelocationStep step = plan.steps().get(0);
        String family = defaultFamily(entry);
        String command = "AUTOSTORE".equalsIgnoreCase(family) ? "BIN_RELOCATE" : "RELOCATE";
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", step.huId());
        putIfPresent(payload, "huCode", step.huCode());
        putIfPresent(payload, "fromLocationId", step.fromLocationId());
        putIfPresent(payload, "toLocationId", step.toLocationId());
        putIfPresent(payload, "forHuId", entry.getHuId());
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), family, null, command, payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setRelocateTaskId(taskId);
        log.info("dig-out for hu {} (induction entry {}): dispatched {} task {} moving blocker {} "
                        + "from location {} to {} ({} step(s) in the plan); RETRIEVE waits until the "
                        + "channel is clear", entry.getHuCode(), entry.getId(), command, taskId,
                step.huCode() != null ? step.huCode() : step.huId(), step.fromLocationId(),
                step.toLocationId(), plan.steps().size());
        return true;
    }

    /**
     * RELOCATE/BIN_RELOCATE device task COMPLETED: the blocker physically moved channels. Book the
     * BLOCKER's new registry location (isolated like every booking), write a {@code RELOCATED} trace
     * row for the blocker, clear {@code relocate_task_id} and re-run the dispatch decision for the
     * entry — the next blocker gets its RELOCATE, or (channel clear) the original RETRIEVE finally
     * goes out. On failure: clear the task link and leave the entry {@code REQUESTED} so the meter
     * pass / next request retries (mirrors failed-retrieve semantics).
     */
    @Transactional
    public void onRelocateCompleted(UUID relocateTaskId, boolean succeeded, Map<String, Object> payload,
                                    String actor) {
        InductionQueueEntry entry = entries.findByRelocateTaskId(relocateTaskId).orElse(null);
        if (entry == null) {
            return; // RELOCATE not tied to an induction dig-out
        }
        if (!succeeded) {
            log.warn("RELOCATE task {} FAILED for hu {} (induction entry {}): dig-out chain stopped, "
                            + "entry left REQUESTED so the next meter pass retries", relocateTaskId,
                    entry.getHuCode(), entry.getId());
            entry.setRelocateTaskId(null);
            return;
        }
        UUID blockerHuId = uuidOf(payload, "huId");
        String blockerHuCode = stringOf(payload, "huCode");
        UUID from = uuidOf(payload, "fromLocationId");
        UUID to = uuidOf(payload, "toLocationId");

        // The blocker left its channel: book its registry row into the new slot (best-effort,
        // isolated: a booking failure must never break the dig-out chain).
        if (blockerHuId != null && to != null) {
            try {
                inventory.bookLocation(blockerHuId, to);
            } catch (RuntimeException e) {
                log.warn("location booking to {} failed for blocker hu {} ({}) after relocate task {}; "
                                + "registry stale but dig-out continues: {}", to,
                        blockerHuCode != null ? blockerHuCode : blockerHuId, blockerHuId, relocateTaskId,
                        e.toString());
            }
        }

        String target = entry.getHuCode() != null ? entry.getHuCode() : String.valueOf(entry.getHuId());
        log.info("dig-out for hu {} (induction entry {}): blocker hu {} relocated from location {} to {} "
                        + "(task {}); re-running the dispatch decision (next blocker or the RETRIEVE)",
                target, entry.getId(), blockerHuCode != null ? blockerHuCode : blockerHuId, from, to,
                relocateTaskId);
        trace.record(entry.getWarehouseId(), blockerHuId, blockerHuCode,
                to == null ? "slot" : "slot:" + to, "RELOCATED",
                "relocated out of channel for " + target,
                from == null ? "slot" : "slot:" + from, null, entry.getWorkplaceId(), relocateTaskId,
                entry.getId());

        entry.setRelocateTaskId(null);
        // Re-run the dispatch decision: next blocker → next RELOCATE; channel clear → the RETRIEVE.
        dispatchRetrieve(entry, defaultFamily(entry), actor);
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
            log.warn("RETRIEVE task {} FAILED for hu {} (induction entry {}): entry left REQUESTED, "
                            + "task link cleared so the next meter pass re-dispatches", retrieveTaskId,
                    entry.getHuCode(), entry.getId());
            entry.setRetrieveTaskId(null);
            return;
        }
        if (!"REQUESTED".equals(entry.getStatus())) {
            return; // already advanced — idempotent
        }
        entry.setStatus("IN_TRANSIT");
        entry.setInTransitAt(java.time.Instant.now());
        log.info("induction entry {}: hu {} REQUESTED -> IN_TRANSIT (RETRIEVE task {} completed); "
                        + "dispatching the CONVEY leg to workplace {}", entry.getId(), entry.getHuCode(),
                retrieveTaskId, entry.getWorkplaceId());

        String slot = entry.getLocationId() == null ? "slot" : "slot:" + entry.getLocationId();
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), slot, "RETRIEVED",
                "retrieved from source slot", null, "conveyor", entry.getWorkplaceId(), retrieveTaskId,
                entry.getId());
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "conveyor", "INDUCTED",
                "inducted onto conveyor", null, null, entry.getWorkplaceId(), retrieveTaskId, entry.getId());

        // The tote left its slot: book the HU registry out of the location (null = in transit / at
        // a workplace; the transport trace is the truth while away).
        bookLocation(entry, null);

        dispatchConvey(entry, actor);
    }

    private void dispatchConvey(InductionQueueEntry entry, String actor) {
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "destinationWorkplaceId", entry.getWorkplaceId());
        assignLiveRoute(entry, payload,
                transportNodes.storageCandidates(entry.getWarehouseId(),
                        TransportNodeResolver.StorageDirection.OUTBOUND),
                transportNodes.destinationCandidates(entry.getWarehouseId(), entry.getWorkplaceId()));
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), "CONVEYOR", null, "CONVEY", payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setConveyTaskId(taskId);
        log.info("induction entry {}: dispatched CONVEY task {} for hu {} to workplace {} ({})",
                entry.getId(), taskId, entry.getHuCode(), entry.getWorkplaceId(),
                payload.containsKey("entryNode")
                        ? "live walk " + payload.get("entryNode") + " -> " + payload.get("destinationNode")
                        : "atomic, no route plan");
    }

    /**
     * ADR-0008 §1: when BOTH transport endpoints resolve on the projected routing graph, assign the
     * HU a route plan to the destination and put {@code entryNode} (+ informational
     * {@code destinationNode}) in the task payload — the emulator/adapter then runs the CONVEY as a
     * live scan-driven walk. When either endpoint is missing (un-projected warehouse), the payload
     * is left untouched so the adapter falls back to today's atomic behaviour.
     */
    private void assignLiveRoute(InductionQueueEntry entry, Map<String, Object> payload,
                                 List<String> entryCandidates, List<String> destinationCandidates) {
        if (entryCandidates.isEmpty() || destinationCandidates.isEmpty() || entry.getHuCode() == null) {
            log.debug("induction entry {} dispatching CONVEY without a route plan "
                            + "(entryCandidates={}, destinationCandidates={}, huCode={})", entry.getId(),
                    entryCandidates.size(), destinationCandidates.size(), entry.getHuCode());
            return;
        }
        // Pair the best entry with the best destination the graph can actually connect. A candidate
        // that LOOKS right can be a dead-end (e.g. the ASRS's inbound stub end has out-degree 0) —
        // assigning it blindly makes the live walk fail its very first scan with "no path".
        RoutingService.PathChecker reachable = routing.pathChecker(entry.getWarehouseId());
        for (String dest : destinationCandidates) {
            for (String from : entryCandidates) {
                // A transport from a node to itself is the degenerate proximity-pollution case
                // (observed live: entry=ASRS-1#1 dest=ASRS-1#0 picked for a PP1->storage return).
                if (from.equals(dest) || !reachable.exists(from, dest)) {
                    continue;
                }
                routing.assignRoute(new RouteRequest(entry.getWarehouseId(), entry.getHuCode(),
                        List.of(dest)));
                payload.put("entryNode", from);
                payload.put("destinationNode", dest);
                log.info("induction entry {}: live route plan for hu {} assigned {} -> {} "
                                + "(first reachable pair of {} entry x {} destination candidates)",
                        entry.getId(), entry.getHuCode(), from, dest, entryCandidates.size(),
                        destinationCandidates.size());
                return;
            }
        }
        log.warn("induction entry {}: no reachable entry->destination pair on the projected graph "
                + "for hu {} ({} entry x {} destination candidates); dispatching atomically without "
                + "a route plan", entry.getId(), entry.getHuCode(),
                entryCandidates.size(), destinationCandidates.size());
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
            log.warn("CONVEY task {} FAILED for hu {} (induction entry {}): entry stays IN_TRANSIT, "
                            + "tote did not arrive at workplace {}", conveyTaskId, entry.getHuCode(),
                    entry.getId(), entry.getWorkplaceId());
            return;
        }
        if (!"IN_TRANSIT".equals(entry.getStatus())) {
            return; // already QUEUED/DONE — idempotent
        }
        long seq = nextArrivalSeq(entry.getWorkplaceId());
        entry.setStatus("QUEUED");
        entry.setQueuedAt(java.time.Instant.now());
        entry.setArrivalSeq(seq);
        log.info("induction entry {}: hu {} IN_TRANSIT -> QUEUED at workplace {} (CONVEY task {} "
                        + "completed, arrival_seq {})", entry.getId(), entry.getHuCode(),
                entry.getWorkplaceId(), conveyTaskId, seq);

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
            // Divert/recirculate decisions are INFO: they explain why arrival diverged from request order.
            log.info("conveyor decision for hu {} at {}: {} ({}), reported by CONVEY task {}",
                    entry.getHuCode(), point, event, decision, conveyTaskId);
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
     * Frees a cap slot, so the workplace's REQUESTED backlog is re-metered. Completing the entry also
     * starts the return-to-storage leg: a return CONVEY (station → storage) is dispatched exactly
     * once; its callback then dispatches the STORE back into the source slot (§4.4).
     */
    @Transactional
    public InductionEntryView markDone(UUID entryId, String family, String actor) {
        InductionQueueEntry entry = entries.findById(entryId)
                .orElseThrow(() -> new InductionEntryNotFoundException(entryId));
        if ("DONE".equals(entry.getStatus())) {
            return InductionEntryView.from(entry);
        }
        String before = entry.getStatus();
        entry.setStatus("DONE");
        entry.setDoneAt(java.time.Instant.now());
        log.info("induction entry {}: hu {} {} -> DONE at workplace {} (operator {}); starting "
                        + "the return-to-storage leg and re-metering the backlog", entry.getId(),
                entry.getHuCode(), before, entry.getWorkplaceId(), actor);
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "station:" + entry.getWorkplaceId(),
                "DONE", "presentation completed", null, null, entry.getWorkplaceId(), null, entry.getId());

        // Return leg: convey the tote back to storage. Guarded so a return CONVEY is never
        // dispatched twice (the DONE early-return above already makes repeat calls no-ops).
        if (entry.getReturnConveyTaskId() == null) {
            dispatchReturnConvey(entry, actor);
        }

        // A slot freed: re-meter the backlog for this workplace.
        meterRetrievals(entry.getWorkplaceId(), family, actor);
        return InductionEntryView.from(entry);
    }

    // ---- §4.4 return-to-storage leg -------------------------------------------------------------

    private void dispatchReturnConvey(InductionQueueEntry entry, String actor) {
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "sourceWorkplaceId", entry.getWorkplaceId());
        putIfPresent(payload, "returnLocationId", entry.getLocationId());
        // Return leg: the roles swap — entry = the workplace's node, destination = the storage node.
        assignLiveRoute(entry, payload,
                transportNodes.destinationCandidates(entry.getWarehouseId(), entry.getWorkplaceId()),
                transportNodes.storageCandidates(entry.getWarehouseId(),
                        TransportNodeResolver.StorageDirection.RETURN));
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), "CONVEYOR", null, "CONVEY", payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setReturnConveyTaskId(taskId);
        log.info("induction entry {}: dispatched return CONVEY task {} for hu {} from workplace {} "
                        + "back to storage ({})", entry.getId(), taskId, entry.getHuCode(),
                entry.getWorkplaceId(),
                payload.containsKey("entryNode")
                        ? "live walk " + payload.get("entryNode") + " -> " + payload.get("destinationNode")
                        : "atomic, no route plan");

        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "conveyor", "RETURNING",
                "returning to storage", "station:" + entry.getWorkplaceId(), null, entry.getWorkplaceId(),
                taskId, entry.getId());
    }

    /**
     * Return CONVEY device task COMPLETED (= arrival back at storage): trace {@code RETURN_ARRIVED}
     * and dispatch the STORE/BIN_STORE back into the source slot. Idempotent: once the store task is
     * linked, a late/duplicate callback is a no-op.
     */
    @Transactional
    public void onReturnConveyCompleted(UUID returnConveyTaskId, boolean succeeded, String actor) {
        InductionQueueEntry entry = entries.findByReturnConveyTaskId(returnConveyTaskId).orElse(null);
        if (entry == null) {
            return; // CONVEY not tied to a return leg (e.g. the outbound induction convey)
        }
        if (!succeeded) {
            log.warn("return CONVEY task {} FAILED for hu {} (induction entry {}): tote did not arrive "
                            + "back at storage, no STORE dispatched", returnConveyTaskId, entry.getHuCode(),
                    entry.getId());
            return;
        }
        if (entry.getReturnStoreTaskId() != null) {
            return; // store already dispatched: idempotent
        }
        log.info("induction entry {}: hu {} arrived back at storage (return CONVEY task {} completed); "
                        + "dispatching the STORE into location {}", entry.getId(), entry.getHuCode(),
                returnConveyTaskId, entry.getLocationId());
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "storage", "RETURN_ARRIVED",
                "arrived back at storage", "conveyor", null, entry.getWorkplaceId(), returnConveyTaskId,
                entry.getId());

        dispatchReturnStore(entry, actor);
    }

    private void dispatchReturnStore(InductionQueueEntry entry, String actor) {
        String family = defaultFamily(entry);
        String command = "AUTOSTORE".equalsIgnoreCase(family) ? "BIN_STORE" : "STORE";
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "locationId", entry.getLocationId());
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), family, null, command, payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setReturnStoreTaskId(taskId);
        log.info("induction entry {}: dispatched return {} task {} for hu {} into location {} ({} family)",
                entry.getId(), command, taskId, entry.getHuCode(), entry.getLocationId(), family);
    }

    /**
     * STORE/BIN_STORE device task COMPLETED: the tote is back in its source slot, so trace
     * {@code STORED}, closing the HU's timeline. On failure: log only.
     */
    @Transactional
    public void onReturnStoreCompleted(UUID returnStoreTaskId, boolean succeeded, String actor) {
        InductionQueueEntry entry = entries.findByReturnStoreTaskId(returnStoreTaskId).orElse(null);
        if (entry == null) {
            return; // STORE not tied to an induction return leg
        }
        if (!succeeded) {
            log.warn("return STORE task {} FAILED for hu {} (induction entry {}): tote not booked back "
                            + "into location {}, timeline left open", returnStoreTaskId, entry.getHuCode(),
                    entry.getId(), entry.getLocationId());
            return;
        }
        log.info("induction entry {}: hu {} stored back into location {} (STORE task {} completed); "
                        + "transport round trip closed", entry.getId(), entry.getHuCode(),
                entry.getLocationId(), returnStoreTaskId);
        String slot = entry.getLocationId() == null ? "slot" : "slot:" + entry.getLocationId();
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), slot, "STORED",
                "stored back to source slot", "storage", null, entry.getWorkplaceId(), returnStoreTaskId,
                entry.getId());

        // The tote is physically back in its source slot: book the HU registry back into it.
        bookLocation(entry, entry.getLocationId());
    }

    /**
     * Best-effort HU registry location booking (§5 reality tracking). Isolated like the projection's
     * side effects: a booking failure must NEVER break the transport pipeline — log.warn and continue
     * (the HU transport trace still tells the truth). Silently skipped when the entry has no huId.
     */
    private void bookLocation(InductionQueueEntry entry, UUID locationId) {
        if (entry.getHuId() == null) {
            return;
        }
        try {
            inventory.bookLocation(entry.getHuId(), locationId);
        } catch (RuntimeException e) {
            log.warn("location booking to {} failed for hu {} ({}, induction entry {}); registry stale "
                            + "but the transport pipeline continues: {}", locationId, entry.getHuCode(),
                    entry.getHuId(), entry.getId(), e.toString());
        }
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

    /**
     * A UUID payload value, tolerant of the JSONB round trip (values come back as Strings once the
     * task is re-read from the DB) and of a missing/malformed entry (null).
     */
    private static UUID uuidOf(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringOf(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : value.toString();
    }
}
