package org.openwcs.flow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.ConnectionDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorLoop;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorControllerRepository;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorLoopRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects a warehouse's conveyor routing graph ({@link ConveyorNode} + {@link ConveyorEdge}) from
 * the automation-topology PLACEMENT model (placed equipment with their {@code path}/{@code sections},
 * equipment-to-equipment connections and named function points). This is the deterministic v1 seam
 * that turns the 3D layout into a routable graph: path waypoints become nodes, directed sections
 * become edges, function points alias the layout nodes to named PLC node codes, and connections
 * stitch equipment together exit→entry. The result fully REPLACES the warehouse routing graph,
 * mirroring {@link TopologyService#replace}.
 *
 * <p>Equipment master-data (family/type) is not visible in this service, so classification is
 * structural: a usable {@code path} (≥2 points) with at least one section → a polyline conveyor /
 * ASRS-with-stubs; otherwise a meaningful {@code lengthM} → a straight box conveyor (start/end
 * nodes); otherwise a single node at the placement centre (rack / sorter / other).
 */
@Service
public class RoutingProjectionService {

    private static final double MIN_BOX_LENGTH_M = 0.05;
    /** A function point within this arc-distance of an existing path point aliases it (no split). */
    private static final double ON_POINT_TOLERANCE_M = 0.05;
    /** Two nodes of DIFFERENT equipment this close (world metres) are treated as physically connected
     *  — an edge is inferred across the touchpoint, so connections need not be drawn by hand. */
    private static final double ADJACENCY_M = 1.5;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RoutingProjectionService.class);

    private final AutomationTopologyService automation;
    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;
    private final ConveyorControllerRepository controllers;
    private final org.openwcs.flow.client.GtpClient gtp;

    public RoutingProjectionService(AutomationTopologyService automation, ConveyorNodeRepository nodes,
                                    ConveyorEdgeRepository edges, ConveyorLoopRepository loops,
                                    ConveyorControllerRepository controllers,
                                    org.openwcs.flow.client.GtpClient gtp) {
        this.automation = automation;
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
        this.controllers = controllers;
        this.gtp = gtp;
    }

    /** Result of a projection: how many nodes/edges were generated and any non-fatal warnings. */
    public record ProjectionResult(int nodes, int edges, List<String> warnings) {
    }

    /** A node staged in memory before persistence, keyed by its (current) unique code. */
    private static final class StagedNode {
        String code;
        final Double posX;
        final Double posY;
        /** The loop this node belongs to (its code), or null. Set when its conveyor is a loop. */
        String loopCode;

        StagedNode(String code, Double posX, Double posY) {
            this.code = code;
            this.posX = posX;
            this.posY = posY;
        }
    }

    /** A loop staged in memory before persistence. */
    private static final class StagedLoop {
        final String code;
        final int maxHus;

        StagedLoop(String code, int maxHus) {
            this.code = code;
            this.maxHus = maxHus;
        }
    }

    /** An edge staged in memory by from/to node codes. */
    private static final class StagedEdge {
        String fromCode;
        String toCode;
        int cost;
        String exitCode;
        /** World positions of the edge endpoints (for FP mid-section splitting); null when off-path. */
        double fromX;
        double fromZ;
        double toX;
        double toZ;
        boolean hasGeometry;

        StagedEdge(String fromCode, String toCode, int cost, String exitCode) {
            this.fromCode = fromCode;
            this.toCode = toCode;
            this.cost = cost;
            this.exitCode = exitCode;
        }

        void geometry(double fromX, double fromZ, double toX, double toZ) {
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toZ = toZ;
            this.hasGeometry = true;
        }
    }

    @Transactional
    public ProjectionResult project(UUID warehouseId) {
        AutomationTopologyDto model = automation.load(warehouseId);
        List<String> warnings = new ArrayList<>();

        // Staged nodes keyed by code (codes are kept globally unique within this projection).
        Map<String, StagedNode> stagedByCode = new LinkedHashMap<>();
        List<StagedEdge> stagedEdges = new ArrayList<>();
        List<StagedLoop> stagedLoops = new ArrayList<>();
        // (equipmentId, pathIndex) -> current node code.
        Map<String, String> codeByEquipPoint = new HashMap<>();
        // equipmentId -> ordered list of path indices that became nodes (entry = first, exit = last).
        Map<UUID, List<Integer>> pointsByEquip = new HashMap<>();

        List<PlacedEquipmentDto> equipment = model.equipment() == null ? List.of() : model.equipment();

        // Function points grouped by the equipment they sit on, ordered by their offset along it.
        Map<UUID, List<FunctionPointDto>> fpsByEquip = new HashMap<>();
        if (model.functionPoints() != null) {
            for (FunctionPointDto fp : model.functionPoints()) {
                if (fp.placedId() == null) {
                    continue;
                }
                fpsByEquip.computeIfAbsent(fp.placedId(), k -> new ArrayList<>()).add(fp);
            }
            for (List<FunctionPointDto> list : fpsByEquip.values()) {
                list.sort((a, b) -> Double.compare(num(a.offsetM()), num(b.offsetM())));
            }
        }

        for (PlacedEquipmentDto e : equipment) {
            List<List<Double>> path = e.path();
            List<List<Integer>> sections = e.sections();
            boolean hasPath = path != null && path.size() >= 2;
            boolean hasSection = sections != null && !sections.isEmpty();
            String cat = category(e);

            // A category that has no routable path collapses to a single node regardless of geometry.
            boolean pointKind = (cat != null && noPathKind(cat)) && !(hasPath && hasSection);

            if (!pointKind && hasPath && hasSection) {
                // Collect every path index referenced by a section, in ascending order.
                TreeSet<Integer> used = new TreeSet<>();
                for (List<Integer> s : sections) {
                    if (s != null && s.size() >= 2 && validIndex(s.get(0), path) && validIndex(s.get(1), path)) {
                        used.add(s.get(0));
                        used.add(s.get(1));
                    }
                }
                if (used.isEmpty()) {
                    warnings.add("Equipment " + label(e) + " has sections but none reference valid path points; "
                            + "treated as a single node.");
                    stageSingle(e, stagedByCode, codeByEquipPoint, pointsByEquip);
                    continue;
                }
                List<Integer> ordered = new ArrayList<>(used);
                pointsByEquip.put(e.id(), ordered);
                for (int idx : ordered) {
                    String code = uniqueCode(nodeCode(e, idx), stagedByCode);
                    List<Double> p = path.get(idx);
                    stagedByCode.put(code, new StagedNode(code, p.get(0), p.get(1)));
                    codeByEquipPoint.put(key(e.id(), idx), code);
                }
                List<StagedEdge> equipEdges = new ArrayList<>();
                for (List<Integer> s : sections) {
                    if (s == null || s.size() < 2 || !validIndex(s.get(0), path) || !validIndex(s.get(1), path)) {
                        continue;
                    }
                    int i = s.get(0);
                    int j = s.get(1);
                    String fromCode = codeByEquipPoint.get(key(e.id(), i));
                    String toCode = codeByEquipPoint.get(key(e.id(), j));
                    if (fromCode == null || toCode == null) {
                        continue;
                    }
                    int cost = (int) Math.max(1, Math.round(distance(path.get(i), path.get(j))));
                    StagedEdge edge = new StagedEdge(fromCode, toCode, cost, toCode);
                    List<Double> pi = path.get(i);
                    List<Double> pj = path.get(j);
                    edge.geometry(pi.get(0), pi.get(1), pj.get(0), pj.get(1));
                    equipEdges.add(edge);
                }
                // Feature 2: insert / alias the equipment's function points before staging its edges.
                List<String> insertedFpCodes = new ArrayList<>();
                applyFunctionPoints(e, path, equipEdges, stagedByCode, codeByEquipPoint,
                        fpsByEquip.get(e.id()), insertedFpCodes, warnings);
                stagedEdges.addAll(equipEdges);

                // Feature 3: a closed path or a cyclic section graph is a loop.
                if (isLoop(e, ordered, sections)) {
                    stageLoop(e, stagedByCode, codeByEquipPoint, ordered, insertedFpCodes, stagedLoops);
                }
            } else if (!pointKind && isBoxKind(e, cat)) {
                double[] start = boxEndpoint(e, true);
                double[] end = boxEndpoint(e, false);
                int last = lastIndex(e);
                String startCode = uniqueCode(nodeCode(e, 0), stagedByCode);
                stagedByCode.put(startCode, new StagedNode(startCode, start[0], start[1]));
                codeByEquipPoint.put(key(e.id(), 0), startCode);
                String endCode = uniqueCode(nodeCode(e, last), stagedByCode);
                stagedByCode.put(endCode, new StagedNode(endCode, end[0], end[1]));
                codeByEquipPoint.put(key(e.id(), last), endCode);
                pointsByEquip.put(e.id(), List.of(0, last));
                int cost = (int) Math.max(1, Math.round(distance(
                        List.of(start[0], start[1]), List.of(end[0], end[1]))));
                stagedEdges.add(new StagedEdge(startCode, endCode, cost, endCode));
            } else {
                stageSingle(e, stagedByCode, codeByEquipPoint, pointsByEquip);
            }
        }

        // Connections: exit node of FROM equipment -> entry node of TO equipment.
        if (model.connections() != null) {
            for (ConnectionDto c : model.connections()) {
                String exit = exitCode(c.fromPlacedId(), pointsByEquip, codeByEquipPoint);
                String entry = entryCode(c.toPlacedId(), pointsByEquip, codeByEquipPoint);
                if (exit == null || entry == null) {
                    warnings.add("Connection " + connLabel(c) + " skipped: could not resolve "
                            + (exit == null ? "FROM exit node" : "TO entry node") + ".");
                    continue;
                }
                stagedEdges.add(new StagedEdge(exit, entry, 1, entry));
            }
        }

        // Auto-inferred connections: when a node of one equipment sits within ADJACENCY_M of a node
        // of ANOTHER, link them (both directions — flow direction is governed by each conveyor's own
        // sections) so material can cross the touchpoint without a hand-drawn connection (e.g. an
        // ASRS infeed stub meeting a conveyor). Only the closest node pair per equipment pair is
        // linked, and existing edges are never duplicated.
        Set<String> edgePairs = new HashSet<>();
        for (StagedEdge se : stagedEdges) {
            edgePairs.add(se.fromCode + ">" + se.toCode);
        }
        List<UUID> eqIds = new ArrayList<>(pointsByEquip.keySet());
        for (int ai = 0; ai < eqIds.size(); ai++) {
            for (int bi = ai + 1; bi < eqIds.size(); bi++) {
                StagedNode na = null;
                StagedNode nb = null;
                double best = ADJACENCY_M;
                for (int ia : pointsByEquip.get(eqIds.get(ai))) {
                    StagedNode sa = stagedByCode.get(codeByEquipPoint.get(key(eqIds.get(ai), ia)));
                    if (sa == null || sa.posX == null || sa.posY == null) {
                        continue;
                    }
                    for (int ib : pointsByEquip.get(eqIds.get(bi))) {
                        StagedNode sb = stagedByCode.get(codeByEquipPoint.get(key(eqIds.get(bi), ib)));
                        if (sb == null || sb.posX == null || sb.posY == null) {
                            continue;
                        }
                        double d = Math.hypot(sa.posX - sb.posX, sa.posY - sb.posY);
                        if (d <= best) {
                            best = d;
                            na = sa;
                            nb = sb;
                        }
                    }
                }
                if (na != null && nb != null) {
                    if (edgePairs.add(na.code + ">" + nb.code)) {
                        stagedEdges.add(new StagedEdge(na.code, nb.code, 1, nb.code));
                    }
                    if (edgePairs.add(nb.code + ">" + na.code)) {
                        stagedEdges.add(new StagedEdge(nb.code, na.code, 1, na.code));
                    }
                }
            }
        }

        // Persist the routing graph FIRST so the projection always succeeds and returns, then project
        // the GTP station nodes as a fully isolated, best-effort side effect. The node sync must never
        // slow down or break "Generate routing" (a slow/unreachable gtp is bounded by a short timeout
        // in GtpClient, and any failure is swallowed here).
        ProjectionResult result = persist(warehouseId, stagedByCode, stagedEdges, stagedLoops, warnings);
        try {
            syncStationNodes(model);
        } catch (Throwable t) {
            log.warn("station-node projection failed (ignored): {}", t.toString());
        }
        return result;
    }

    /**
     * For every workstation placement bound to a GTP station, turn its STOCK/ORDER conveyor
     * interactions (connections tagged in {@code label}) into the station's STOCK/ORDER nodes, with
     * the feeding conveyor point's offset carried as the inbound distance. Best-effort: a gtp call
     * failing (or no station bound) never fails the routing projection.
     */
    private void syncStationNodes(AutomationTopologyDto model) {
        if (model.connections() == null || model.equipment() == null) {
            return;
        }
        Map<UUID, FunctionPointDto> fpById = new HashMap<>();
        if (model.functionPoints() != null) {
            for (FunctionPointDto fp : model.functionPoints()) {
                fpById.put(fp.id(), fp);
            }
        }
        for (PlacedEquipmentDto e : model.equipment()) {
            if (e.stationId() == null || !"workstation".equals(category(e))) {
                continue;
            }
            List<org.openwcs.flow.client.GtpClient.NodeSpec> specs = new ArrayList<>();
            for (var c : model.connections()) {
                String role = c.label() == null ? null : c.label().trim().toUpperCase();
                if (!"STOCK".equals(role) && !"ORDER".equals(role)) {
                    continue;
                }
                UUID pointId;
                if (e.id().equals(c.fromPlacedId())) {
                    pointId = c.toPointId();
                } else if (e.id().equals(c.toPlacedId())) {
                    pointId = c.fromPointId();
                } else {
                    continue;
                }
                FunctionPointDto fp = pointId == null ? null : fpById.get(pointId);
                if (fp == null) {
                    continue;
                }
                String code = fp.nodeCode() != null && !fp.nodeCode().isBlank() ? fp.nodeCode() : fp.name();
                specs.add(new org.openwcs.flow.client.GtpClient.NodeSpec(role, code, null, null, fp.offsetM()));
            }
            try {
                gtp.syncStationNodes(e.stationId(), specs);
            } catch (RuntimeException ex) {
                log.warn("could not project topology nodes to gtp station {}: {}", e.stationId(), ex.toString());
            }
        }
    }

    /**
     * Bind a conveyor's function points to its routing graph (Feature 2). For each FP, by arc-length
     * {@code offsetM} along the path: if it lands essentially ON a path point that became a node, keep
     * the v1 aliasing (rename that node to the FP's nodeCode); otherwise it sits mid-section, so split
     * the section's edge by inserting a node at the FP position, chaining multiple FPs on one section
     * in offset order ({@code i -> fp1 -> fp2 -> ... -> j}) with cost split proportionally to the
     * sub-segment lengths (each at least 1).
     */
    private void applyFunctionPoints(PlacedEquipmentDto e, List<List<Double>> path,
                                     List<StagedEdge> equipEdges, Map<String, StagedNode> stagedByCode,
                                     Map<String, String> codeByEquipPoint, List<FunctionPointDto> fps,
                                     List<String> insertedFpCodes, List<String> warnings) {
        if (fps == null || fps.isEmpty()) {
            return;
        }
        int fpIndex = 0;
        for (FunctionPointDto fp : fps) {
            int index = fpIndex++;
            double offset = num(fp.offsetM());
            double[] pos = pointAlong(path, offset);
            if (pos == null) {
                continue;
            }
            // If the FP is essentially ON an existing path point of this equipment, alias it (v1).
            Integer onIdx = pathPointAt(e, path, codeByEquipPoint, pos);
            if (onIdx != null) {
                if (fp.nodeCode() == null || fp.nodeCode().isBlank()) {
                    continue;
                }
                String oldCode = codeByEquipPoint.get(key(e.id(), onIdx));
                if (oldCode == null) {
                    continue;
                }
                aliasNode(oldCode, fp.nodeCode().trim(), stagedByCode, codeByEquipPoint, equipEdges, warnings);
                continue;
            }
            // Mid-section: find the edge whose segment contains the FP position and split it.
            StagedEdge host = containingEdge(equipEdges, pos);
            if (host == null) {
                warnings.add("Function point " + fpLabel(fp) + " at offset " + offset
                        + " m is not on any section of " + label(e) + "; skipped.");
                continue;
            }
            String desired = (fp.nodeCode() != null && !fp.nodeCode().isBlank())
                    ? fp.nodeCode().trim() : sanitise(eqCode(e)) + "#fp" + index;
            String fpCode = uniqueCode(desired, stagedByCode);
            if (!fpCode.equals(desired) && fp.nodeCode() != null && !fp.nodeCode().isBlank()) {
                warnings.add("Function-point nodeCode '" + desired + "' collided; inserted as '"
                        + fpCode + "'.");
            }
            stagedByCode.put(fpCode, new StagedNode(fpCode, pos[0], pos[1]));
            insertedFpCodes.add(fpCode);
            splitEdge(host, fpCode, pos, equipEdges);
        }
    }

    // ---- persistence (full replace, mirroring TopologyService.replace) -------------------------

    private ProjectionResult persist(UUID warehouseId, Map<String, StagedNode> stagedByCode,
                                     List<StagedEdge> stagedEdges, List<StagedLoop> stagedLoops,
                                     List<String> warnings) {
        edges.deleteByWarehouseId(warehouseId);
        loops.deleteByWarehouseId(warehouseId);
        controllers.deleteByWarehouseId(warehouseId);
        nodes.deleteAll(nodes.findByWarehouseId(warehouseId));
        nodes.flush();

        for (StagedLoop sl : stagedLoops) {
            ConveyorLoop loop = new ConveyorLoop();
            loop.setWarehouseId(warehouseId);
            loop.setCode(sl.code);
            loop.setMaxHus(sl.maxHus);
            loop.setWhenFull("HOLD");
            loops.save(loop);
        }

        Map<String, UUID> idByCode = new HashMap<>();
        for (StagedNode sn : stagedByCode.values()) {
            ConveyorNode node = new ConveyorNode();
            node.setWarehouseId(warehouseId);
            node.setCode(sn.code);
            node.setPosX(sn.posX);
            node.setPosY(sn.posY);
            node.setLoopCode(sn.loopCode);
            idByCode.put(sn.code, nodes.save(node).getId());
        }

        int edgeCount = 0;
        for (StagedEdge se : stagedEdges) {
            UUID from = idByCode.get(se.fromCode);
            UUID to = idByCode.get(se.toCode);
            if (from == null || to == null) {
                warnings.add("Edge " + se.fromCode + " -> " + se.toCode
                        + " skipped: endpoint did not persist.");
                continue;
            }
            ConveyorEdge edge = new ConveyorEdge();
            edge.setWarehouseId(warehouseId);
            edge.setFromNodeId(from);
            edge.setToNodeId(to);
            edge.setExitCode(se.exitCode == null ? se.toCode : se.exitCode);
            edge.setCost(se.cost);
            edges.save(edge);
            edgeCount++;
        }
        return new ProjectionResult(idByCode.size(), edgeCount, warnings);
    }

    // ---- staging helpers -----------------------------------------------------------------------

    private void stageSingle(PlacedEquipmentDto e, Map<String, StagedNode> stagedByCode,
                             Map<String, String> codeByEquipPoint, Map<UUID, List<Integer>> pointsByEquip) {
        String code = uniqueCode(nodeCode(e, 0), stagedByCode);
        stagedByCode.put(code, new StagedNode(code, num(e.posXM()), num(e.posZM())));
        codeByEquipPoint.put(key(e.id(), 0), code);
        pointsByEquip.put(e.id(), List.of(0));
    }

    /** Alias (rename) the layout node a function point sits on to the FP's nodeCode (v1 behaviour),
     *  fixing up the index map and any of this equipment's edges referencing the old code. */
    private void aliasNode(String oldCode, String desired, Map<String, StagedNode> stagedByCode,
                           Map<String, String> codeByEquipPoint, List<StagedEdge> equipEdges,
                           List<String> warnings) {
        String newCode = oldCode.equals(desired) ? desired : uniqueCode(desired, stagedByCode);
        if (!newCode.equals(desired)) {
            warnings.add("Function-point nodeCode '" + desired + "' collided; aliased as '" + newCode + "'.");
        }
        if (newCode.equals(oldCode)) {
            return;
        }
        StagedNode node = stagedByCode.remove(oldCode);
        if (node == null) {
            return;
        }
        node.code = newCode;
        stagedByCode.put(newCode, node);
        for (Map.Entry<String, String> en : codeByEquipPoint.entrySet()) {
            if (en.getValue().equals(oldCode)) {
                en.setValue(newCode);
            }
        }
        for (StagedEdge se : equipEdges) {
            if (se.fromCode.equals(oldCode)) {
                se.fromCode = newCode;
            }
            if (se.toCode.equals(oldCode)) {
                se.toCode = newCode;
            }
            if (oldCode.equals(se.exitCode)) {
                se.exitCode = newCode;
            }
        }
    }

    /** Split a host edge {@code i -> j} at an inserted FP node, replacing it in place with
     *  {@code i -> fp} and appending {@code fp -> j}, splitting cost proportionally to the
     *  sub-segment lengths (each at least 1). Geometry is updated so later FPs on the same original
     *  segment chain onto the correct sub-edge. */
    private void splitEdge(StagedEdge host, String fpCode, double[] pos, List<StagedEdge> equipEdges) {
        double segLen = Math.hypot(host.toX - host.fromX, host.toZ - host.fromZ);
        double dFrom = Math.hypot(pos[0] - host.fromX, pos[1] - host.fromZ);
        double frac = segLen > 0 ? Math.min(1.0, Math.max(0.0, dFrom / segLen)) : 0.5;
        int total = host.cost;
        int firstCost = (int) Math.max(1, Math.min(total - 1 > 0 ? total - 1 : total, Math.round(total * frac)));
        int secondCost = Math.max(1, total - firstCost);

        String origToCode = host.toCode;
        double origToX = host.toX;
        double origToZ = host.toZ;

        // host becomes from -> fp; its exit now points at the inserted node.
        host.toCode = fpCode;
        host.exitCode = fpCode;
        host.cost = firstCost;
        host.toX = pos[0];
        host.toZ = pos[1];

        // tail: fp -> j, retaining the original target as its exit.
        StagedEdge tail = new StagedEdge(fpCode, origToCode, secondCost, origToCode);
        tail.geometry(pos[0], pos[1], origToX, origToZ);
        equipEdges.add(tail);
    }

    /** The edge whose segment contains {@code pos} (within a small tolerance), or null. */
    private static StagedEdge containingEdge(List<StagedEdge> equipEdges, double[] pos) {
        StagedEdge best = null;
        double bestDist = ON_POINT_TOLERANCE_M;
        for (StagedEdge se : equipEdges) {
            if (!se.hasGeometry) {
                continue;
            }
            double d = pointToSegment(pos[0], pos[1], se.fromX, se.fromZ, se.toX, se.toZ);
            if (d <= bestDist) {
                bestDist = d;
                best = se;
            }
        }
        return best;
    }

    // ---- classification & geometry -------------------------------------------------------------

    /** The lower-cased trimmed category, or null when absent/blank (→ geometric fallback). */
    private static String category(PlacedEquipmentDto e) {
        String c = e.category();
        if (c == null || c.isBlank()) {
            return null;
        }
        return c.trim().toLowerCase();
    }

    /** Categories that, when they carry NO routable path, collapse to a single node. */
    private static boolean noPathKind(String cat) {
        return "asrs".equals(cat) || "sorter".equals(cat) || "manual-storage".equals(cat)
                || "other".equals(cat);
    }

    /** Whether this equipment should be staged as a straight start/end box. Prefer the supplied
     *  category ({@code conveyor} → box when it has a usable length); fall back to the geometric
     *  heuristic (a meaningful lengthM) when no category was supplied. */
    private static boolean isBoxKind(PlacedEquipmentDto e, String cat) {
        boolean hasLength = e.lengthM() != null && e.lengthM().doubleValue() > MIN_BOX_LENGTH_M;
        if (cat == null) {
            return hasLength;
        }
        return "conveyor".equals(cat) && hasLength;
    }

    /** Start (true) or end (false) endpoint of a straight box along its length, rotated by yaw.
     *  Mirrors the UI {@code pointAlong} box case: endpoint = centre ± (length/2) along yaw. */
    private static double[] boxEndpoint(PlacedEquipmentDto e, boolean start) {
        double yaw = Math.toRadians(num(e.rotationDeg()));
        double ux = Math.cos(yaw);
        double uz = Math.sin(yaw);
        double half = num(e.lengthM()) / 2.0;
        double cx = num(e.posXM());
        double cz = num(e.posZM());
        double sign = start ? -1.0 : 1.0;
        return new double[] {cx + sign * ux * half, cz + sign * uz * half};
    }

    private static int lastIndex(PlacedEquipmentDto e) {
        // A straight box has a notional second path index of 1 unless it already carries waypoints.
        List<List<Double>> path = e.path();
        return path != null && path.size() >= 2 ? path.size() - 1 : 1;
    }

    private static boolean validIndex(Integer idx, List<List<Double>> path) {
        return idx != null && idx >= 0 && idx < path.size() && path.get(idx) != null && path.get(idx).size() >= 2;
    }

    private static double distance(List<Double> a, List<Double> b) {
        double dx = a.get(0) - b.get(0);
        double dz = a.get(1) - b.get(1);
        return Math.hypot(dx, dz);
    }

    /** The world position at arc-length {@code offsetM} along the polyline (clamped to the ends).
     *  Mirrors the UI {@code pointAlong} polyline walk. Null when there is no usable path. */
    private static double[] pointAlong(List<List<Double>> path, double offsetM) {
        if (path == null || path.size() < 2) {
            return null;
        }
        if (offsetM <= 0) {
            List<Double> p = path.get(0);
            return new double[] {p.get(0), p.get(1)};
        }
        double remaining = offsetM;
        for (int i = 0; i + 1 < path.size(); i++) {
            List<Double> a = path.get(i);
            List<Double> b = path.get(i + 1);
            double seg = distance(a, b);
            if (seg <= 0) {
                continue;
            }
            if (remaining <= seg) {
                double t = remaining / seg;
                return new double[] {a.get(0) + (b.get(0) - a.get(0)) * t,
                        a.get(1) + (b.get(1) - a.get(1)) * t};
            }
            remaining -= seg;
        }
        List<Double> last = path.get(path.size() - 1);
        return new double[] {last.get(0), last.get(1)};
    }

    /** The equipment path index that became a node and sits within {@link #ON_POINT_TOLERANCE_M} of
     *  {@code pos}, or null when {@code pos} is genuinely mid-section. */
    private static Integer pathPointAt(PlacedEquipmentDto e, List<List<Double>> path,
                                       Map<String, String> codeByEquipPoint, double[] pos) {
        if (path == null) {
            return null;
        }
        for (int idx = 0; idx < path.size(); idx++) {
            if (codeByEquipPoint.get(key(e.id(), idx)) == null) {
                continue;
            }
            List<Double> p = path.get(idx);
            if (Math.hypot(p.get(0) - pos[0], p.get(1) - pos[1]) <= ON_POINT_TOLERANCE_M) {
                return idx;
            }
        }
        return null;
    }

    /** Shortest distance from point (px,pz) to the segment (ax,az)-(bx,bz). */
    private static double pointToSegment(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double len2 = dx * dx + dz * dz;
        if (len2 <= 0) {
            return Math.hypot(px - ax, pz - az);
        }
        double t = ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.min(1.0, Math.max(0.0, t));
        double cx = ax + t * dx;
        double cz = az + t * dz;
        return Math.hypot(px - cx, pz - cz);
    }

    // ---- connection endpoint resolution --------------------------------------------------------

    private static String exitCode(UUID equipId, Map<UUID, List<Integer>> pointsByEquip,
                                   Map<String, String> codeByEquipPoint) {
        List<Integer> pts = equipId == null ? null : pointsByEquip.get(equipId);
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        return codeByEquipPoint.get(key(equipId, pts.get(pts.size() - 1)));
    }

    private static String entryCode(UUID equipId, Map<UUID, List<Integer>> pointsByEquip,
                                    Map<String, String> codeByEquipPoint) {
        List<Integer> pts = equipId == null ? null : pointsByEquip.get(equipId);
        if (pts == null || pts.isEmpty()) {
            return null;
        }
        return codeByEquipPoint.get(key(equipId, pts.get(0)));
    }

    // ---- codes -------------------------------------------------------------------------------

    /** Stable base code for an equipment instance: its code, or EQ-<short id>. */
    private static String eqCode(PlacedEquipmentDto e) {
        return e.code() != null && !e.code().isBlank() ? e.code().trim() : "EQ-" + shortId(e.id());
    }

    /** Base node code for an equipment point: equipment code (or EQ-<short id>) + '#' + index. */
    private static String nodeCode(PlacedEquipmentDto e, int index) {
        return sanitise(eqCode(e)) + "#" + index;
    }

    private static String uniqueCode(String desired, Map<String, StagedNode> stagedByCode) {
        if (!stagedByCode.containsKey(desired)) {
            return desired;
        }
        int n = 2;
        String candidate;
        do {
            candidate = desired + "-" + n++;
        } while (stagedByCode.containsKey(candidate));
        return candidate;
    }

    private static String sanitise(String raw) {
        String s = raw.replaceAll("\\s+", "_");
        return s.isBlank() ? "EQ" : s;
    }

    private static String shortId(UUID id) {
        return id == null ? "x" : id.toString().substring(0, 8);
    }

    private static String key(UUID equipId, int index) {
        return equipId + "@" + index;
    }

    // ---- loops (Feature 3) ---------------------------------------------------------------------

    /** Whether a path-conveyor is a loop: it is flagged {@code closed}, OR its directed sections form
     *  a single cycle that visits every node point (in-degree and out-degree 1 everywhere, with the
     *  reachable set covering all node points). */
    private static boolean isLoop(PlacedEquipmentDto e, List<Integer> nodePoints, List<List<Integer>> sections) {
        if (e.closed()) {
            return true;
        }
        if (nodePoints == null || nodePoints.size() < 2 || sections == null) {
            return false;
        }
        Map<Integer, Integer> outDeg = new HashMap<>();
        Map<Integer, Integer> inDeg = new HashMap<>();
        Map<Integer, Integer> next = new HashMap<>();
        for (List<Integer> s : sections) {
            if (s == null || s.size() < 2) {
                continue;
            }
            int i = s.get(0);
            int j = s.get(1);
            outDeg.merge(i, 1, Integer::sum);
            inDeg.merge(j, 1, Integer::sum);
            next.put(i, j);
        }
        // Every node point must have exactly one in- and one out-edge for a simple directed cycle.
        for (int p : nodePoints) {
            if (outDeg.getOrDefault(p, 0) != 1 || inDeg.getOrDefault(p, 0) != 1) {
                return false;
            }
        }
        // Walk the successor chain from the first point; it must return after visiting every point.
        int start = nodePoints.get(0);
        Set<Integer> seen = new HashSet<>();
        int cur = start;
        for (int steps = 0; steps < nodePoints.size(); steps++) {
            if (!seen.add(cur)) {
                return false;
            }
            Integer nxt = next.get(cur);
            if (nxt == null) {
                return false;
            }
            cur = nxt;
        }
        return cur == start && seen.size() == nodePoints.size();
    }

    /** Stage a loop for a conveyor and tag every node it generated (path nodes + inserted function
     *  points) with the loop code. */
    private void stageLoop(PlacedEquipmentDto e, Map<String, StagedNode> stagedByCode,
                           Map<String, String> codeByEquipPoint, List<Integer> nodePoints,
                           List<String> insertedFpCodes, List<StagedLoop> stagedLoops) {
        String loopCode = uniqueLoopCode(sanitise(eqCode(e)) + "-loop", stagedLoops);
        for (int idx : nodePoints) {
            tagLoop(stagedByCode.get(codeByEquipPoint.get(key(e.id(), idx))), loopCode);
        }
        for (String fpCode : insertedFpCodes) {
            tagLoop(stagedByCode.get(fpCode), loopCode);
        }
        // maxHus default: the number of node points around the loop (a sane non-zero capacity).
        stagedLoops.add(new StagedLoop(loopCode, nodePoints.size()));
    }

    private static void tagLoop(StagedNode node, String loopCode) {
        if (node != null) {
            node.loopCode = loopCode;
        }
    }

    private static String uniqueLoopCode(String desired, List<StagedLoop> stagedLoops) {
        Set<String> taken = new HashSet<>();
        for (StagedLoop l : stagedLoops) {
            taken.add(l.code);
        }
        if (!taken.contains(desired)) {
            return desired;
        }
        int n = 2;
        String candidate;
        do {
            candidate = desired + "-" + n++;
        } while (taken.contains(candidate));
        return candidate;
    }

    private static double num(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static String label(PlacedEquipmentDto e) {
        return e.code() != null && !e.code().isBlank() ? e.code() : e.id().toString();
    }

    private static String fpLabel(FunctionPointDto fp) {
        return fp.name() != null && !fp.name().isBlank() ? fp.name() : fp.id().toString();
    }

    private static String connLabel(ConnectionDto c) {
        return c.label() != null && !c.label().isBlank() ? c.label() : c.id().toString();
    }
}
