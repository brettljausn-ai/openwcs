package org.openwcs.flow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.ConnectionDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.domain.ConveyorEdge;
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

    private final AutomationTopologyService automation;
    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;
    private final ConveyorControllerRepository controllers;

    public RoutingProjectionService(AutomationTopologyService automation, ConveyorNodeRepository nodes,
                                    ConveyorEdgeRepository edges, ConveyorLoopRepository loops,
                                    ConveyorControllerRepository controllers) {
        this.automation = automation;
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
        this.controllers = controllers;
    }

    /** Result of a projection: how many nodes/edges were generated and any non-fatal warnings. */
    public record ProjectionResult(int nodes, int edges, List<String> warnings) {
    }

    /** A node staged in memory before persistence, keyed by its (current) unique code. */
    private static final class StagedNode {
        String code;
        final Double posX;
        final Double posY;

        StagedNode(String code, Double posX, Double posY) {
            this.code = code;
            this.posX = posX;
            this.posY = posY;
        }
    }

    /** An edge staged in memory by from/to node codes. */
    private static final class StagedEdge {
        String fromCode;
        String toCode;
        final int cost;
        String exitCode;

        StagedEdge(String fromCode, String toCode, int cost, String exitCode) {
            this.fromCode = fromCode;
            this.toCode = toCode;
            this.cost = cost;
            this.exitCode = exitCode;
        }
    }

    @Transactional
    public ProjectionResult project(UUID warehouseId) {
        AutomationTopologyDto model = automation.load(warehouseId);
        List<String> warnings = new ArrayList<>();

        // Staged nodes keyed by code (codes are kept globally unique within this projection).
        Map<String, StagedNode> stagedByCode = new LinkedHashMap<>();
        List<StagedEdge> stagedEdges = new ArrayList<>();
        // (equipmentId, pathIndex) -> current node code.
        Map<String, String> codeByEquipPoint = new HashMap<>();
        // equipmentId -> ordered list of path indices that became nodes (entry = first, exit = last).
        Map<UUID, List<Integer>> pointsByEquip = new HashMap<>();

        List<PlacedEquipmentDto> equipment = model.equipment() == null ? List.of() : model.equipment();

        for (PlacedEquipmentDto e : equipment) {
            List<List<Double>> path = e.path();
            List<List<Integer>> sections = e.sections();
            boolean hasPath = path != null && path.size() >= 2;
            boolean hasSection = sections != null && !sections.isEmpty();

            if (hasPath && hasSection) {
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
                    stagedEdges.add(new StagedEdge(fromCode, toCode, cost, toCode));
                }
            } else if (isBox(e)) {
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

        // Function-point node-code aliasing: rename the layout node nearest a named function point to
        // the FP's nodeCode. This is the seam binding named PLC nodes to the projected layout.
        if (model.functionPoints() != null) {
            for (FunctionPointDto fp : model.functionPoints()) {
                if (fp.nodeCode() == null || fp.nodeCode().isBlank() || fp.placedId() == null) {
                    continue;
                }
                List<Integer> pts = pointsByEquip.get(fp.placedId());
                if (pts == null || pts.isEmpty()) {
                    warnings.add("Function point " + fpLabel(fp) + " has nodeCode '" + fp.nodeCode()
                            + "' but its equipment produced no path nodes; alias skipped.");
                    continue;
                }
                PlacedEquipmentDto eq = findEquipment(equipment, fp.placedId());
                int nearestIdx = nearestPathIndex(eq, pts, fp.offsetM());
                String oldCode = codeByEquipPoint.get(key(fp.placedId(), nearestIdx));
                if (oldCode == null) {
                    continue;
                }
                String desired = fp.nodeCode().trim();
                String newCode = oldCode.equals(desired) ? desired : uniqueCode(desired, stagedByCode);
                if (!newCode.equals(desired)) {
                    warnings.add("Function-point nodeCode '" + desired + "' collided; aliased as '"
                            + newCode + "'.");
                }
                if (!newCode.equals(oldCode)) {
                    rename(oldCode, newCode, stagedByCode, codeByEquipPoint, stagedEdges);
                }
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

        return persist(warehouseId, stagedByCode, stagedEdges, warnings);
    }

    // ---- persistence (full replace, mirroring TopologyService.replace) -------------------------

    private ProjectionResult persist(UUID warehouseId, Map<String, StagedNode> stagedByCode,
                                     List<StagedEdge> stagedEdges, List<String> warnings) {
        edges.deleteByWarehouseId(warehouseId);
        loops.deleteByWarehouseId(warehouseId);
        controllers.deleteByWarehouseId(warehouseId);
        nodes.deleteAll(nodes.findByWarehouseId(warehouseId));
        nodes.flush();

        Map<String, UUID> idByCode = new HashMap<>();
        for (StagedNode sn : stagedByCode.values()) {
            ConveyorNode node = new ConveyorNode();
            node.setWarehouseId(warehouseId);
            node.setCode(sn.code);
            node.setPosX(sn.posX);
            node.setPosY(sn.posY);
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

    /** Rename a staged node's code and fix up the index map and any edges referencing the old code. */
    private void rename(String oldCode, String newCode, Map<String, StagedNode> stagedByCode,
                        Map<String, String> codeByEquipPoint, List<StagedEdge> stagedEdges) {
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
        for (StagedEdge se : stagedEdges) {
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

    // ---- classification & geometry -------------------------------------------------------------

    private static boolean isBox(PlacedEquipmentDto e) {
        return e.lengthM() != null && e.lengthM().doubleValue() > MIN_BOX_LENGTH_M;
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

    /** The path index whose arc-length position is closest to a function point's offset (metres). */
    private static int nearestPathIndex(PlacedEquipmentDto e, List<Integer> candidates, BigDecimal offsetM) {
        List<List<Double>> path = e == null ? null : e.path();
        if (path == null || path.size() < 2 || candidates.size() == 1) {
            return candidates.get(0);
        }
        double target = num(offsetM);
        // Cumulative arc-length from path[0] to each candidate index.
        double best = Double.MAX_VALUE;
        int bestIdx = candidates.get(0);
        for (int idx : candidates) {
            double arc = arcLengthTo(path, idx);
            double d = Math.abs(arc - target);
            if (d < best) {
                best = d;
                bestIdx = idx;
            }
        }
        return bestIdx;
    }

    private static double arcLengthTo(List<List<Double>> path, int index) {
        double sum = 0;
        for (int i = 0; i < index && i + 1 < path.size(); i++) {
            sum += distance(path.get(i), path.get(i + 1));
        }
        return sum;
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

    /** Base node code for an equipment point: equipment code (or EQ-<short id>) + '#' + index. */
    private static String nodeCode(PlacedEquipmentDto e, int index) {
        String base = e.code() != null && !e.code().isBlank() ? e.code().trim() : "EQ-" + shortId(e.id());
        return sanitise(base) + "#" + index;
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

    private static PlacedEquipmentDto findEquipment(List<PlacedEquipmentDto> equipment, UUID id) {
        for (PlacedEquipmentDto e : equipment) {
            if (id.equals(e.id())) {
                return e;
            }
        }
        return null;
    }

    private static String key(UUID equipId, int index) {
        return equipId + "@" + index;
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
