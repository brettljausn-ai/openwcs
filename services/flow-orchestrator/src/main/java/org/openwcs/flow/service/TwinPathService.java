package org.openwcs.flow.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.flow.api.TwinPathDtos.TotePath;
import org.openwcs.flow.api.TwinPathDtos.TwinPaths;
import org.openwcs.flow.api.TwinPathDtos.Waypoint;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.domain.HuTransportTrace;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The live-twin "visu master": resolves each in-transit handling unit's ACTUAL path through the
 * conveyor topology, in world XZ metres, from the data the orchestrator already owns — the running
 * CONVEY tasks (which totes move), the SCANNED transport trace (the ordered nodes each tote passed,
 * with real timestamps and the routed-to next node), and the routing-node positions
 * ({@code conveyor_node.pos_x/pos_y}, the same world coordinates the 3D scene renders).
 *
 * <p>Why this exists: the frontend previously reconstructed motion by PROJECTING scanned positions
 * onto the nearest drawn belt to recover "how far along" a tote was. At a divert two branches meet
 * at one point and the projection is ambiguous, so totes flew to a belt's start and snapped back.
 * The backend has no such ambiguity: it logged the exact node sequence. Handing the frontend that
 * resolved polyline (positions baked in) removes the entire class of guess-the-belt bugs and, with
 * {@code serverNowMs}, the clock-skew bug too.
 */
@Service
public class TwinPathService {

    private static final Logger log = LoggerFactory.getLogger(TwinPathService.class);

    /** Device-task statuses that mean "this CONVEY is still moving the tote" (mirrors the twin UI). */
    private static final Set<String> ACTIVE = Set.of("REQUESTED", "DISPATCHED", "IN_PROGRESS", "PENDING");

    private final DeviceTaskRepository tasks;
    private final HuTransportTraceRepository traces;
    private final ConveyorNodeRepository nodes;

    public TwinPathService(DeviceTaskRepository tasks, HuTransportTraceRepository traces,
                           ConveyorNodeRepository nodes) {
        this.tasks = tasks;
        this.traces = traces;
        this.nodes = nodes;
    }

    @Transactional(readOnly = true)
    public TwinPaths paths(UUID warehouseId) {
        long nowMs = Instant.now().toEpochMilli();

        Map<String, ConveyorNode> nodeByCode = new HashMap<>();
        for (ConveyorNode n : nodes.findByWarehouseId(warehouseId)) {
            nodeByCode.put(n.getCode(), n);
        }

        // The latest in-flight CONVEY task per HU bounds that HU's current transit leg.
        Map<UUID, DeviceTask> latestByHu = new LinkedHashMap<>();
        Map<UUID, String> codeByHu = new HashMap<>();
        for (DeviceTask t : tasks.findActiveConvey(warehouseId, ACTIVE)) { // newest first
            UUID huId = payloadUuid(t, "huId");
            if (huId == null) {
                continue;
            }
            latestByHu.putIfAbsent(huId, t); // first seen == latest
            codeByHu.putIfAbsent(huId, payloadString(t, "huCode"));
        }

        List<TotePath> totes = new ArrayList<>(latestByHu.size());
        for (Map.Entry<UUID, DeviceTask> e : latestByHu.entrySet()) {
            UUID huId = e.getKey();
            Instant legStart = e.getValue().getCreatedAt() != null ? e.getValue().getCreatedAt() : Instant.EPOCH;
            List<HuTransportTrace> rows = traces
                    .findByWarehouseIdAndHuIdAndEventAndTsGreaterThanEqualOrderByTsAsc(
                            warehouseId, huId, "SCANNED", legStart);

            List<Waypoint> waypoints = new ArrayList<>(rows.size());
            String nextCode = null;
            boolean recirculating = false;
            for (HuTransportTrace r : rows) {
                ConveyorNode n = nodeByCode.get(r.getPoint());
                if (n == null || n.getPosX() == null || n.getPosY() == null) {
                    continue; // a non-node point (e.g. "conveyor"/"sorter") — not a positionable waypoint
                }
                waypoints.add(new Waypoint(r.getPoint(), n.getPosX(), n.getPosY(),
                        r.getTs() == null ? null : r.getTs().toEpochMilli()));
                nextCode = r.getToPoint();
                if (isRecirculate(r.getDecision())) {
                    recirculating = true;
                }
            }
            if (waypoints.isEmpty()) {
                continue; // no positionable scans yet — the tote has no honest position to show
            }
            Waypoint next = resolve(nodeByCode, nextCode);
            totes.add(new TotePath(huId, codeByHu.get(huId),
                    recirculating ? "recirculating" : "in-transit", waypoints, next));
        }

        log.debug("twin paths for warehouse {}: {} in-transit totes", warehouseId, totes.size());
        return new TwinPaths(nowMs, totes);
    }

    private static Waypoint resolve(Map<String, ConveyorNode> nodeByCode, String code) {
        if (code == null) {
            return null;
        }
        ConveyorNode n = nodeByCode.get(code);
        if (n == null || n.getPosX() == null || n.getPosY() == null) {
            return null;
        }
        return new Waypoint(code, n.getPosX(), n.getPosY(), null);
    }

    private static boolean isRecirculate(String decision) {
        if (decision == null) {
            return false;
        }
        String d = decision.toUpperCase();
        return d.contains("RECIRC") || d.contains("HELD");
    }

    private static UUID payloadUuid(DeviceTask t, String key) {
        Object v = t.getPayload() == null ? null : t.getPayload().get(key);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String payloadString(DeviceTask t, String key) {
        Object v = t.getPayload() == null ? null : t.getPayload().get(key);
        return v == null ? null : v.toString();
    }
}
