package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.domain.FlowMoveChain;
import org.openwcs.flow.repo.FlowMoveChainRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates a CROSS-SYSTEM physical move (source and destination served by different storage
 * systems / transport families) as a multi-leg chain {@code RETRIEVE → CONVEY → STORE}, mirroring
 * the induction return-leg orchestration ({@link InductionQueueService}):
 *
 * <ol>
 *   <li><b>RETRIEVE</b> the HU out of the source system (AUTOSTORE ⇒ BIN_RETRIEVE), persisting a
 *       {@link FlowMoveChain} row in {@code PENDING}.</li>
 *   <li>On RETRIEVE completion, advance to {@code RETRIEVED} and dispatch a <b>CONVEY</b> routed
 *       from the source's discharge to the destination's entry using the same
 *       {@link TransportNodeResolver} / {@link RoutingService#assignRoute} machinery induction uses
 *       (atomic fallback on an un-projected warehouse).</li>
 *   <li>On CONVEY completion, advance to {@code CONVEYED} and dispatch the <b>STORE</b> into the
 *       destination location (AUTOSTORE ⇒ BIN_STORE).</li>
 *   <li>On STORE completion, advance to {@code STORED}, book the HU's destination location in
 *       inventory and (via {@link DeviceTaskService} auditing the terminal STORE) fire
 *       {@code HandlingUnitMoved}.</li>
 * </ol>
 *
 * <p>Each leg advances exactly once: the chain is looked up by the completing task's own column
 * ({@code retrieve/convey/store_task_id}) and the transition is guarded by the chain's status, so a
 * late/duplicate callback is a no-op. A failed leg marks the chain {@code FAILED} and dispatches
 * nothing — state stays consistent and inspectable. The same-system case never reaches this service;
 * it stays a single RELOCATE booked by {@link DeviceTaskService#bookFlowMoveLocation}.
 *
 * <p>The store-leg booking is done HERE (mirroring induction's {@code onReturnStoreCompleted}), NOT
 * by {@code bookFlowMoveLocation}: a chain STORE carries no {@code moveSource=flow-move} tag, so that
 * path is a no-op for it (no double booking).
 */
@Service
public class FlowMoveChainService {

    private static final Logger log = LoggerFactory.getLogger(FlowMoveChainService.class);

    private final FlowMoveChainRepository chains;
    private final DeviceTaskService deviceTasks;
    private final TransportNodeResolver transportNodes;
    private final RoutingService routing;
    private final org.openwcs.flow.client.InventoryClient inventory;

    /**
     * {@code deviceTasks} is injected {@link Lazy} to break the dispatch↔callback cycle, exactly as
     * {@link DeviceTaskService} injects {@link InductionQueueService}: this service dispatches device
     * tasks through {@code deviceTasks}, and {@code deviceTasks} advances chains from its callback.
     */
    public FlowMoveChainService(FlowMoveChainRepository chains, @Lazy DeviceTaskService deviceTasks,
                                TransportNodeResolver transportNodes, RoutingService routing,
                                org.openwcs.flow.client.InventoryClient inventory) {
        this.chains = chains;
        this.deviceTasks = deviceTasks;
        this.transportNodes = transportNodes;
        this.routing = routing;
        this.inventory = inventory;
    }

    // ---- leg 1: start the chain with a RETRIEVE -------------------------------------------------

    /**
     * Persist a {@code PENDING} chain and dispatch the RETRIEVE leg out of the source system. Returns
     * the RETRIEVE device-task id (the first leg) — the {@code POST /api/flow/moves} response.
     */
    @Transactional
    public UUID start(UUID warehouseId, UUID huId, String huCode, UUID fromLocationId,
                      UUID toLocationId, String sourceFamily, String destFamily, String reason,
                      String actor) {
        FlowMoveChain chain = new FlowMoveChain();
        chain.setWarehouseId(warehouseId);
        chain.setHuId(huId);
        chain.setHuCode(huCode);
        chain.setFromLocationId(fromLocationId);
        chain.setToLocationId(toLocationId);
        chain.setSourceFamily(sourceFamily);
        chain.setDestFamily(destFamily);
        chain.setReason(reason);
        chain.setStatus("PENDING");
        chains.saveAndFlush(chain);

        String command = "AUTOSTORE".equalsIgnoreCase(sourceFamily) ? "BIN_RETRIEVE" : "RETRIEVE";
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", huId);
        putIfPresent(payload, "huCode", huCode);
        putIfPresent(payload, "locationId", fromLocationId);
        RequestDeviceTask req = new RequestDeviceTask(warehouseId, sourceFamily, null, command, payload, huId);
        UUID taskId = deviceTasks.request(req, actor).id();
        chain.setRetrieveTaskId(taskId);
        log.info("flow move chain {}: hu {} ({}) cross-system {} -> {} ({} -> {}); dispatched {} task "
                        + "{} (leg 1/3, reason {})", chain.getId(), huCode, huId, fromLocationId,
                toLocationId, sourceFamily, destFamily, command, taskId, reason);
        return taskId;
    }

    // ---- leg 2: RETRIEVE completed -> CONVEY ----------------------------------------------------

    /**
     * RETRIEVE leg completed: advance {@code PENDING → RETRIEVED} and dispatch the CONVEY leg routed
     * from the source discharge to the destination entry. Idempotent: only advances from
     * {@code PENDING}; a failed leg marks the chain {@code FAILED} and dispatches nothing.
     */
    @Transactional
    public void onRetrieveCompleted(UUID retrieveTaskId, boolean succeeded, String actor) {
        FlowMoveChain chain = chains.findByRetrieveTaskId(retrieveTaskId).orElse(null);
        if (chain == null) {
            return; // RETRIEVE not tied to a cross-system move chain (e.g. an induction retrieve)
        }
        if (!succeeded) {
            failChain(chain, "RETRIEVE", retrieveTaskId);
            return;
        }
        if (!"PENDING".equals(chain.getStatus())) {
            return; // already advanced — idempotent
        }
        chain.setStatus("RETRIEVED");
        log.info("flow move chain {}: hu {} PENDING -> RETRIEVED (RETRIEVE task {} completed); "
                        + "dispatching the CONVEY leg to destination location {}", chain.getId(),
                chain.getHuCode(), retrieveTaskId, chain.getToLocationId());
        dispatchConvey(chain, actor);
    }

    private void dispatchConvey(FlowMoveChain chain, String actor) {
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", chain.getHuId());
        putIfPresent(payload, "huCode", chain.getHuCode());
        putIfPresent(payload, "destinationLocationId", chain.getToLocationId());
        // Route source discharge (OUTBOUND storage) -> destination entry (RETURN storage), reusing
        // the induction routing machinery; un-projected warehouses dispatch atomically (no route plan).
        assignLiveRoute(chain, payload,
                transportNodes.storageCandidates(chain.getWarehouseId(),
                        TransportNodeResolver.StorageDirection.OUTBOUND),
                transportNodes.storageCandidates(chain.getWarehouseId(),
                        TransportNodeResolver.StorageDirection.RETURN));
        RequestDeviceTask req = new RequestDeviceTask(
                chain.getWarehouseId(), "CONVEYOR", null, "CONVEY", payload, chain.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        chain.setConveyTaskId(taskId);
        log.info("flow move chain {}: dispatched CONVEY task {} for hu {} ({})", chain.getId(), taskId,
                chain.getHuCode(),
                payload.containsKey("entryNode")
                        ? "live walk " + payload.get("entryNode") + " -> " + payload.get("destinationNode")
                        : "atomic, no route plan");
    }

    // ---- leg 3: CONVEY completed -> STORE -------------------------------------------------------

    /**
     * CONVEY leg completed (HU arrived at the destination system's entry): advance
     * {@code RETRIEVED → CONVEYED} and dispatch the STORE into the destination location. Idempotent:
     * only advances from {@code RETRIEVED}; a failed leg marks the chain {@code FAILED}.
     */
    @Transactional
    public void onConveyCompleted(UUID conveyTaskId, boolean succeeded, String actor) {
        FlowMoveChain chain = chains.findByConveyTaskId(conveyTaskId).orElse(null);
        if (chain == null) {
            return; // CONVEY not tied to a move chain (e.g. an induction convey)
        }
        if (!succeeded) {
            failChain(chain, "CONVEY", conveyTaskId);
            return;
        }
        if (!"RETRIEVED".equals(chain.getStatus())) {
            return; // already advanced — idempotent
        }
        chain.setStatus("CONVEYED");
        log.info("flow move chain {}: hu {} RETRIEVED -> CONVEYED (CONVEY task {} completed); "
                        + "dispatching the STORE into location {}", chain.getId(), chain.getHuCode(),
                conveyTaskId, chain.getToLocationId());
        dispatchStore(chain, actor);
    }

    private void dispatchStore(FlowMoveChain chain, String actor) {
        String command = "AUTOSTORE".equalsIgnoreCase(chain.getDestFamily()) ? "BIN_STORE" : "STORE";
        Map<String, Object> payload = new HashMap<>();
        putIfPresent(payload, "huId", chain.getHuId());
        putIfPresent(payload, "huCode", chain.getHuCode());
        putIfPresent(payload, "locationId", chain.getToLocationId());
        RequestDeviceTask req = new RequestDeviceTask(
                chain.getWarehouseId(), chain.getDestFamily(), null, command, payload, chain.getHuId());
        UUID taskId = deviceTasks.request(req, actor).id();
        chain.setStoreTaskId(taskId);
        log.info("flow move chain {}: dispatched {} task {} for hu {} into location {} ({} family, leg 3/3)",
                chain.getId(), command, taskId, chain.getHuCode(), chain.getToLocationId(),
                chain.getDestFamily());
    }

    // ---- final: STORE completed -> book destination + close the chain --------------------------

    /**
     * STORE leg completed (the final leg): advance {@code CONVEYED → STORED} and book the HU's
     * destination location in inventory (best-effort, isolated — a booking failure must never break
     * the transition; the move stands, the registry is just stale). The terminal STORE's
     * {@code HandlingUnitMoved} audit is fired by {@link DeviceTaskService} itself. Idempotent: only
     * advances from {@code CONVEYED}; a failed leg marks the chain {@code FAILED}.
     */
    @Transactional
    public void onStoreCompleted(UUID storeTaskId, boolean succeeded, String actor) {
        FlowMoveChain chain = chains.findByStoreTaskId(storeTaskId).orElse(null);
        if (chain == null) {
            return; // STORE not tied to a move chain (e.g. an induction return store)
        }
        if (!succeeded) {
            failChain(chain, "STORE", storeTaskId);
            return;
        }
        if (!"CONVEYED".equals(chain.getStatus())) {
            return; // already STORED — idempotent
        }
        chain.setStatus("STORED");
        log.info("flow move chain {}: hu {} CONVEYED -> STORED (STORE task {} completed); booking the "
                        + "destination location {} — cross-system move closed", chain.getId(),
                chain.getHuCode(), storeTaskId, chain.getToLocationId());
        try {
            inventory.bookLocation(chain.getHuId(), chain.getToLocationId());
        } catch (RuntimeException e) {
            log.warn("flow move chain {}: location booking to {} failed for hu {} ({}); the move stands "
                            + "but the registry is stale: {}", chain.getId(), chain.getToLocationId(),
                    chain.getHuCode(), chain.getHuId(), e.toString());
        }
    }

    private void failChain(FlowMoveChain chain, String leg, UUID taskId) {
        if ("FAILED".equals(chain.getStatus()) || "STORED".equals(chain.getStatus())) {
            return; // already terminal — idempotent
        }
        chain.setStatus("FAILED");
        log.warn("flow move chain {}: {} leg (task {}) FAILED for hu {}; chain stopped at status FAILED, "
                        + "no further leg dispatched (recoverable / inspectable)", chain.getId(), leg,
                taskId, chain.getHuCode());
    }

    // ---- routing helper (mirrors InductionQueueService.assignLiveRoute) -------------------------

    /**
     * When BOTH endpoints resolve on the projected routing graph, assign the HU a route plan to the
     * destination and stamp {@code entryNode}/{@code destinationNode} so the adapter runs the CONVEY
     * as a live scan-driven walk; otherwise leave the payload untouched (atomic fallback). The
     * first reachable entry × destination pair wins — a candidate that looks right may be unreachable.
     */
    private void assignLiveRoute(FlowMoveChain chain, Map<String, Object> payload,
                                 List<String> entryCandidates, List<String> destinationCandidates) {
        if (entryCandidates.isEmpty() || destinationCandidates.isEmpty() || chain.getHuCode() == null) {
            return;
        }
        RoutingService.PathChecker reachable = routing.pathChecker(chain.getWarehouseId());
        for (String dest : destinationCandidates) {
            for (String from : entryCandidates) {
                if (from.equals(dest) || !reachable.exists(from, dest)) {
                    continue;
                }
                routing.assignRoute(new RouteRequest(chain.getWarehouseId(), chain.getHuCode(),
                        List.of(dest)));
                payload.put("entryNode", from);
                payload.put("destinationNode", dest);
                log.info("flow move chain {}: live route plan for hu {} assigned {} -> {}",
                        chain.getId(), chain.getHuCode(), from, dest);
                return;
            }
        }
        log.warn("flow move chain {}: no reachable entry->destination pair on the projected graph for "
                + "hu {}; dispatching the CONVEY atomically without a route plan", chain.getId(),
                chain.getHuCode());
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
