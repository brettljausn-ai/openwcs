package org.openwcs.flow.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.FlowMoveRequest;
import org.openwcs.flow.api.FlowMoveResult;
import org.openwcs.flow.api.RequestDeviceTask;
import org.openwcs.flow.client.MasterDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generic physical-move dispatch (execution-layer item 1): moves a handling unit from one location
 * to another by dispatching the device task(s) that physically carry it.
 *
 * <p>The transport is chosen by where the two locations live:
 * <ul>
 *   <li><b>Same storage system / in-aisle</b> (source and destination in the SAME storage block —
 *       {@code location.block_id}, the slotting pool): a single RELOCATE through the existing
 *       device-task machinery. AUTOSTORE maps to BIN_RELOCATE, every other family to RELOCATE
 *       (ADR-0009), both of which the equipment emulator already simulates. The dispatch is tagged
 *       {@code moveSource=flow-move} so its completion books the HU's new location in inventory (see
 *       {@link DeviceTaskService#bookFlowMoveLocation}) WITHOUT touching the induction dig-out path
 *       that shares the RELOCATE command.</li>
 *   <li><b>Cross-system</b> (different storage blocks, or a storage slot → a conveyor / pick-face on
 *       a different transport family — detected as "no common storage block"): a multi-leg chain
 *       RETRIEVE → CONVEY → STORE driven by {@link FlowMoveChainService}, reusing the induction
 *       return-leg routing machinery. The final STORE completion books the destination location.</li>
 * </ul>
 *
 * <p>Either way the response is {@code { deviceTaskId }} — the id of the first dispatched leg.
 */
@Service
public class FlowMoveService {

    private static final Logger log = LoggerFactory.getLogger(FlowMoveService.class);

    private static final String DEFAULT_FAMILY = "AUTOSTORE";

    private final DeviceTaskService deviceTasks;
    private final FlowMoveChainService chains;
    private final MasterDataClient masterData;

    public FlowMoveService(DeviceTaskService deviceTasks, FlowMoveChainService chains,
                           MasterDataClient masterData) {
        this.deviceTasks = deviceTasks;
        this.chains = chains;
        this.masterData = masterData;
    }

    public FlowMoveResult move(FlowMoveRequest request, String actor) {
        String family = normaliseFamily(request.family());

        if (isCrossSystem(request)) {
            // Cross-system: the source and destination are served by different storage systems, so a
            // single device cannot carry the HU. Run a RETRIEVE -> CONVEY -> STORE chain. An explicit
            // destFamily on the request wins; otherwise resolve the destination's real device family
            // from its storage block (SHUTTLE/CRANE_ASRS->ASRS, AUTOSTORE->AUTOSTORE, AMR_GTP->AMR).
            // When the destination has no block / can't be resolved, fall back to the source family.
            String destFamily = normaliseFamily(
                    request.destFamily() != null ? request.destFamily() : resolveDestFamily(request, family));
            UUID firstLeg = chains.start(request.warehouseId(), request.huId(), request.huCode(),
                    request.fromLocationId(), request.toLocationId(), family, destFamily,
                    request.reason(), actor);
            return new FlowMoveResult(firstLeg);
        }

        String command = "AUTOSTORE".equals(family) ? "BIN_RELOCATE" : "RELOCATE";
        Map<String, Object> payload = new HashMap<>();
        payload.put("huId", request.huId());
        payload.put("huCode", request.huCode());
        payload.put("fromLocationId", request.fromLocationId());
        payload.put("toLocationId", request.toLocationId());
        if (request.reason() != null) {
            payload.put("reason", request.reason());
        }
        // Tag the dispatch so DeviceTaskService books the HU's new location on completion and the
        // induction dig-out relocate path stays a no-op for it.
        payload.put(DeviceTaskService.MOVE_SOURCE_KEY, DeviceTaskService.MOVE_SOURCE_FLOW);

        log.info("flow move (same-system): hu {} ({}) from location {} to {} via {} {} (actor {}, reason {})",
                request.huId(), request.huCode(), request.fromLocationId(), request.toLocationId(),
                family, command, actor, request.reason());

        RequestDeviceTask deviceRequest = new RequestDeviceTask(
                request.warehouseId(), family, null, command, payload, null);
        DeviceTaskView view = deviceTasks.request(deviceRequest, actor);
        return new FlowMoveResult(view.id());
    }

    /**
     * Whether the move crosses storage systems. An explicit {@code crossSystem} flag on the request
     * wins (an integration that already knows the topology can force either path). Otherwise it is
     * decided by the locations' storage blocks ({@code location.block_id}): the move is cross-system
     * unless BOTH ends resolve to the SAME, non-null block. A destination with no block (a conveyor /
     * workplace operational location on a different transport family) is therefore cross-system, and a
     * move with no {@code fromLocationId} (source unknown) falls back to the safe single-RELOCATE
     * path (same-system) so today's behaviour is unchanged when blocks can't be compared.
     */
    private boolean isCrossSystem(FlowMoveRequest request) {
        if (request.crossSystem() != null) {
            return request.crossSystem();
        }
        if (request.fromLocationId() == null) {
            return false; // no source to compare — keep the single-RELOCATE path (unchanged behaviour)
        }
        UUID fromBlock = masterData.blockId(request.warehouseId(), request.fromLocationId());
        UUID toBlock = masterData.blockId(request.warehouseId(), request.toLocationId());
        // Same storage system iff both ends share one real (non-null) block. If the source block can't
        // be resolved at all (master-data down), stay same-system to preserve today's behaviour.
        if (fromBlock == null) {
            return false;
        }
        return !fromBlock.equals(toBlock);
    }

    /**
     * Resolve the destination's real device family from its storage block (the emulator itself is
     * family-blind): look up the destination location's block, then its {@code storageType}, then map
     * it to the routing family (SHUTTLE/CRANE_ASRS → ASRS, AUTOSTORE → AUTOSTORE, AMR_GTP → AMR). When
     * the destination has no block, the block can't be resolved (master-data down), or the type is
     * non-automated/unknown (no device family), fall back to the source {@code family} — preserving
     * the prior "destFamily defaults to the source family" behaviour.
     */
    private String resolveDestFamily(FlowMoveRequest request, String family) {
        UUID toBlock = masterData.blockId(request.warehouseId(), request.toLocationId());
        if (toBlock == null) {
            return family; // no destination block (operational location) — fall back to the source family
        }
        String resolved = MasterDataClient.deviceFamilyOf(
                masterData.blockStorageType(request.warehouseId(), toBlock));
        if (resolved == null) {
            log.info("flow move: destination block {} has no resolvable device family; falling back to "
                    + "the source family {}", toBlock, family);
            return family;
        }
        return resolved;
    }

    private static String normaliseFamily(String family) {
        return (family == null || family.isBlank()) ? DEFAULT_FAMILY : family.trim().toUpperCase();
    }
}
