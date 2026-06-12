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
import org.openwcs.flow.client.MasterDataClient;
import org.openwcs.flow.client.SlottingClient;
import org.openwcs.flow.client.WorkplaceClient;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.domain.InductionQueueEntry;
import org.openwcs.flow.repo.ConveyorNodeRepository;
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
 * operator completes it to {@code DONE}. Completion starts the return-to-storage leg: ONLY
 * slotting decides where the tote goes (never the source slot). When slotting answers, the return
 * CONVEY (station → storage) is routed and its callback dispatches the STORE/BIN_STORE into the
 * slotting-chosen location. When slotting errors or has no answer, the return CONVEY still goes
 * out (the tote must leave the workplace) but with NO destination and NO route plan — the tote
 * stays on the conveyor, the entry is marked awaiting-slot, and a scheduled sweep
 * ({@link #retryAwaitingSlots}) retries slotting until it answers. Each transition appends an
 * {@link HuTraceService} row (§5).
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
    private final MasterDataClient masterData;
    private final ConveyorNodeRepository conveyorNodes;

    public InductionQueueService(InductionQueueEntryRepository entries, HuTraceService trace,
                                 DeviceTaskService deviceTasks, WorkplaceClient workplaces,
                                 TransportNodeResolver transportNodes, RoutingService routing,
                                 InventoryClient inventory, SlottingClient slotting,
                                 MasterDataClient masterData, ConveyorNodeRepository conveyorNodes) {
        this.entries = entries;
        this.trace = trace;
        this.deviceTasks = deviceTasks;
        this.workplaces = workplaces;
        this.transportNodes = transportNodes;
        this.routing = routing;
        this.inventory = inventory;
        this.slotting = slotting;
        this.masterData = masterData;
        this.conveyorNodes = conveyorNodes;
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

        // The tote is on the conveyor now: book the HU to the entry conveyor's operational
        // location (every conveyor automatically has a location named after it). Unresolvable
        // (un-projected warehouse / master-data down) books null — inventory maps it to UNKNOWN.
        bookLocation(entry, conveyorOperationalLocation(entry, payload));
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

        // The tote is physically at the workplace now: book the HU to the workplace's operational
        // location (workplaces automatically have a location named after them too).
        bookLocation(entry, workplaceOperationalLocation(entry));
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
     * once; where the tote goes is decided by SLOTTING alone (§4.4) — never the source slot.
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

    /**
     * Dispatch the return CONVEY (workplace → conveyor). ONLY slotting decides the storage
     * destination: when it answers, the destination is stamped on the entry, the route plan targets
     * the storage entry and arrival fires the STORE into the slotting-chosen location. When
     * slotting errors or has no answer, the CONVEY still goes out — the tote must leave the
     * workplace — but with NO destination and NO route plan: the tote stays ON the conveyor
     * (circulating / following divert defaults), the entry is marked awaiting-slot and the
     * scheduled sweep retries slotting. The source slot is NEVER a fallback destination.
     */
    private void dispatchReturnConvey(InductionQueueEntry entry, String actor) {
        UUID destination = null;
        String slottingFailure = null;
        try {
            destination = slotting
                    .bestLocation(entry.getWarehouseId(), entry.getHuId(), entry.getSkuId(), entry.getQty())
                    .orElse(null);
            if (destination == null) {
                slottingFailure = "slotting returned no put-away location";
            }
        } catch (RuntimeException e) {
            slottingFailure = e.toString();
        }

        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", entry.getHuId());
        putIfPresent(payload, "huCode", entry.getHuCode());
        putIfPresent(payload, "sourceWorkplaceId", entry.getWorkplaceId());
        if (destination != null) {
            entry.setStorageLocationId(destination);
            entry.setAwaitingSlot(false);
            payload.put("destinationLocationId", destination);
            log.info("induction entry {}: slotting answered for hu {} — return leg will STORE into "
                            + "location {}", entry.getId(), entry.getHuCode(), destination);
            // Return leg: the roles swap — entry = the workplace's node, destination = the storage node.
            assignLiveRoute(entry, payload,
                    transportNodes.destinationCandidates(entry.getWarehouseId(), entry.getWorkplaceId()),
                    transportNodes.storageCandidates(entry.getWarehouseId(),
                            TransportNodeResolver.StorageDirection.RETURN));
        } else {
            entry.setAwaitingSlot(true);
            log.warn("induction entry {}: slotting failed for hu {} ({}); tote stays on the conveyor "
                            + "with no storage destination and no route plan, retrying slotting on the "
                            + "awaiting-slot sweep", entry.getId(), entry.getHuCode(), slottingFailure);
        }
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), "CONVEYOR", null, "CONVEY", payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setReturnConveyTaskId(taskId);
        log.info("induction entry {}: dispatched return CONVEY task {} for hu {} from workplace {} "
                        + "onto the conveyor ({})", entry.getId(), taskId, entry.getHuCode(),
                entry.getWorkplaceId(),
                payload.containsKey("entryNode")
                        ? "live walk " + payload.get("entryNode") + " -> " + payload.get("destinationNode")
                        : "atomic, no route plan");

        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "conveyor", "RETURNING",
                destination != null
                        ? "returning to storage location " + destination
                        : "left workplace without a slot (awaiting slotting); circulating on the conveyor",
                "station:" + entry.getWorkplaceId(), null, entry.getWorkplaceId(), taskId, entry.getId());

        // The tote left the workplace onto the conveyor: book the HU to the entry conveyor's
        // operational location (null/UNKNOWN when unresolvable).
        bookLocation(entry, conveyorOperationalLocation(entry, payload));
    }

    /**
     * Return CONVEY device task COMPLETED (= arrival back at storage): trace {@code RETURN_ARRIVED}
     * and dispatch the STORE/BIN_STORE into the slotting-chosen location. An entry still awaiting
     * its slot dispatches NOTHING — the tote stays on the conveyor and the retry sweep fires the
     * STORE once slotting answers. Idempotent: once the store task is linked, a late/duplicate
     * callback is a no-op.
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
        if (entry.getStorageLocationId() == null) {
            log.info("induction entry {}: return CONVEY task {} completed for hu {} but slotting has "
                            + "not answered yet; no STORE dispatched — tote stays on the conveyor, the "
                            + "awaiting-slot sweep stores it once a slot is assigned", entry.getId(),
                    returnConveyTaskId, entry.getHuCode());
            return;
        }
        log.info("induction entry {}: hu {} arrived back at storage (return CONVEY task {} completed); "
                        + "dispatching the STORE into slotting-chosen location {}", entry.getId(),
                entry.getHuCode(), returnConveyTaskId, entry.getStorageLocationId());
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
        putIfPresent(payload, "locationId", entry.getStorageLocationId());
        RequestDeviceTask req = new RequestDeviceTask(
                entry.getWarehouseId(), family, null, command, payload, entry.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        entry.setReturnStoreTaskId(taskId);
        log.info("induction entry {}: dispatched return {} task {} for hu {} into slotting-chosen "
                        + "location {} ({} family)", entry.getId(), command, taskId, entry.getHuCode(),
                entry.getStorageLocationId(), family);
    }

    /**
     * STORE/BIN_STORE device task COMPLETED: the tote is in its slotting-chosen slot, so trace
     * {@code STORED}, closing the HU's timeline, and book the final slot. On failure: log only.
     */
    @Transactional
    public void onReturnStoreCompleted(UUID returnStoreTaskId, boolean succeeded, String actor) {
        InductionQueueEntry entry = entries.findByReturnStoreTaskId(returnStoreTaskId).orElse(null);
        if (entry == null) {
            return; // STORE not tied to an induction return leg
        }
        if (!succeeded) {
            log.warn("return STORE task {} FAILED for hu {} (induction entry {}): tote not booked "
                            + "into location {}, timeline left open", returnStoreTaskId, entry.getHuCode(),
                    entry.getId(), entry.getStorageLocationId());
            return;
        }
        log.info("induction entry {}: hu {} stored into slotting-chosen location {} (STORE task {} "
                        + "completed); transport round trip closed", entry.getId(), entry.getHuCode(),
                entry.getStorageLocationId(), returnStoreTaskId);
        String slot = entry.getStorageLocationId() == null ? "slot" : "slot:" + entry.getStorageLocationId();
        trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), slot, "STORED",
                "stored to slotting-chosen location", "storage", null, entry.getWorkplaceId(),
                returnStoreTaskId, entry.getId());

        // The tote is physically in its slot: book the HU registry into the final location.
        bookLocation(entry, entry.getStorageLocationId());

        // Tell slotting the store happened so the assignment leaves its active ledger — open
        // assignments count as planned occupancy and would otherwise pile up per round trip.
        // Best-effort like the booking: a slotting hiccup must not fail the completed store.
        try {
            slotting.confirmStored(entry.getWarehouseId(), entry.getHuId());
        } catch (RuntimeException e) {
            log.warn("slotting store-confirmation failed for hu {} ({}); assignment ledger stays "
                            + "open but the transport round trip is closed: {}",
                    entry.getHuCode(), entry.getHuId(), e.toString());
        }
    }

    // ---- awaiting-slot retry sweep --------------------------------------------------------------

    /**
     * Retry slotting for every awaiting-slot entry (scheduled ~30s via {@code InductionSlotSweeper}).
     * When slotting answers: stamp the destination, clear the flag, assign the route plan toward the
     * storage entry — routing is per-scan, so an already-walking tote adapts at its next scan — and,
     * when the return CONVEY has already completed (the tote finished its plan-less leg), dispatch
     * the STORE immediately so the arrival → STORE wiring still closes the round trip.
     */
    @Transactional
    public void retryAwaitingSlots(String actor) {
        for (InductionQueueEntry entry : entries.findByAwaitingSlotTrue()) {
            UUID destination;
            try {
                destination = slotting
                        .bestLocation(entry.getWarehouseId(), entry.getHuId(), entry.getSkuId(), entry.getQty())
                        .orElse(null);
            } catch (RuntimeException e) {
                log.warn("awaiting-slot retry for hu {} (induction entry {}) failed; tote stays on the "
                                + "conveyor, retrying: {}", entry.getHuCode(), entry.getId(), e.toString());
                continue;
            }
            if (destination == null) {
                log.warn("awaiting-slot retry for hu {} (induction entry {}): slotting still has no "
                        + "put-away location; tote stays on the conveyor, retrying", entry.getHuCode(),
                        entry.getId());
                continue;
            }
            entry.setStorageLocationId(destination);
            entry.setAwaitingSlot(false);
            log.info("slot assigned mid-journey for hu {} (induction entry {}): slotting answered "
                            + "location {}; the circulating tote adapts at its next scan", entry.getHuCode(),
                    entry.getId(), destination);
            trace.record(entry.getWarehouseId(), entry.getHuId(), entry.getHuCode(), "conveyor",
                    "SLOT_ASSIGNED", "slot assigned mid-journey: " + destination, null, "storage",
                    entry.getWorkplaceId(), entry.getReturnConveyTaskId(), entry.getId());

            // Route the circulating tote toward the storage entry (per-scan: it adapts wherever it is).
            if (entry.getHuCode() != null) {
                List<String> storageTargets = transportNodes.storageCandidates(entry.getWarehouseId(),
                        TransportNodeResolver.StorageDirection.RETURN);
                if (!storageTargets.isEmpty()) {
                    routing.assignRoute(new RouteRequest(entry.getWarehouseId(), entry.getHuCode(),
                            List.of(storageTargets.get(0))));
                }
            }

            // The return CONVEY may have completed while we waited (plan-less leg): fire the STORE now.
            if (entry.getReturnStoreTaskId() == null && entry.getReturnConveyTaskId() != null
                    && "COMPLETED".equals(deviceTasks.get(entry.getReturnConveyTaskId()).status())) {
                log.info("induction entry {}: return CONVEY had already completed while awaiting the "
                                + "slot; dispatching the STORE into location {} now", entry.getId(),
                        destination);
                dispatchReturnStore(entry, actor);
            }
        }
    }

    // ---- operational-location bookings ----------------------------------------------------------

    /**
     * The operational location of the conveyor the tote entered on, resolved from the dispatched
     * payload's {@code entryNode} (node codes look like {@code BIN_CONVEYOR-1#0}: the equipment name
     * is the part before {@code #}; a projected node's own name field wins when set). Returns null
     * when nothing resolves (un-projected warehouse, unknown node, master-data down).
     */
    private UUID conveyorOperationalLocation(InductionQueueEntry entry, Map<String, Object> payload) {
        Object entryNode = payload.get("entryNode");
        if (entryNode == null) {
            return null;
        }
        String nodeCode = entryNode.toString();
        String conveyorName = conveyorNodes
                .findByWarehouseIdAndCode(entry.getWarehouseId(), nodeCode)
                .map(ConveyorNode::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElseGet(() -> {
                    int hash = nodeCode.indexOf('#');
                    return hash > 0 ? nodeCode.substring(0, hash) : nodeCode;
                });
        return masterData.operationalLocation(entry.getWarehouseId(), "EQUIPMENT", conveyorName);
    }

    /**
     * The operational location of the entry's workplace, named by the workplace's code (fetched from
     * gtp; the workplace id string when gtp can't resolve it). Null when master-data can't resolve.
     */
    private UUID workplaceOperationalLocation(InductionQueueEntry entry) {
        if (entry.getWorkplaceId() == null) {
            return null;
        }
        String name = workplaces.code(entry.getWorkplaceId())
                .filter(c -> !c.isBlank())
                .orElse(entry.getWorkplaceId().toString());
        return masterData.operationalLocation(entry.getWarehouseId(), "WORKPLACE", name);
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
