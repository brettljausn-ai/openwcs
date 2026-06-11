package org.openwcs.flow.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.ConnectionDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the routing-graph nodes where a conveyor transport starts and ends (ADR-0008 §1):
 * the node where the ASRS hands totes onto the conveyor ({@link #storageEntryNode}) and the node
 * where the conveyor hands a tote to a workplace ({@link #destinationNode}). On the return leg the
 * roles swap (entry = the workplace's node, destination = the storage node).
 *
 * <p>The resolution mirrors what {@link RoutingProjectionService} projects from the placement
 * model, most faithful first:
 * <ol>
 *   <li><b>Named function points.</b> The projection aliases a function point's {@code nodeCode}
 *       onto the routing graph (it renames the path node the FP sits on, or splits the section it
 *       sits in, see {@code RoutingProjectionService.applyFunctionPoints}), so an FP {@code nodeCode}
 *       IS a projected node code. We take, in order of fit: function points ON the anchor placement
 *       itself (workstation: INDUCT before DISCHARGE; ASRS: DISCHARGE before OUT), then function
 *       points anchored by connections touching the placement — the far-side point of the connection,
 *       exactly as {@code RoutingProjectionService.syncStationNodes} resolves a station's STOCK/ORDER
 *       nodes (STOCK before ORDER before unlabelled). Every candidate is validated against the
 *       projected {@code conveyor_node} codes; an FP whose code never projected is skipped.</li>
 *   <li><b>Nearest node.</b> When no named function point resolves, the projected node closest to
 *       the placement centre wins: the projection stages node positions in world metres (path
 *       waypoints {@code [x,z]}; single-node equipment at {@code posXM}/{@code posZM}, see
 *       {@code RoutingProjectionService.stageSingle}), so placement {@code posXM}/{@code posZM} and
 *       node {@code posX}/{@code posY} share one coordinate system.</li>
 * </ol>
 *
 * <p>Returns {@link Optional#empty()} when the warehouse has no projected routing graph or no
 * matching placement — callers must then fall back to today's atomic (plan-less) dispatch so
 * un-projected warehouses keep working.
 */
@Service
public class TransportNodeResolver {

    private final AutomationTopologyService automation;
    private final ConveyorNodeRepository nodes;

    public TransportNodeResolver(AutomationTopologyService automation, ConveyorNodeRepository nodes) {
        this.automation = automation;
        this.nodes = nodes;
    }

    /**
     * The routing-graph node where the conveyor hands a tote to the given workplace: the
     * workstation placement with {@code stationId == workplaceId}, resolved per the class strategy.
     */
    @Transactional(readOnly = true)
    public Optional<String> destinationNode(UUID warehouseId, UUID workplaceId) {
        return destinationCandidates(warehouseId, workplaceId).stream().findFirst();
    }

    /**
     * ALL plausible workplace nodes, best first: named function points, then connection far-sides,
     * then every projected node by distance to the workstation. The dispatcher pairs these with the
     * entry candidates and picks the first pair the routing graph can actually connect — a candidate
     * that LOOKS right but is unreachable (e.g. a dead-end inbound stub) must never be chosen blindly.
     */
    @Transactional(readOnly = true)
    public List<String> destinationCandidates(UUID warehouseId, UUID workplaceId) {
        List<ConveyorNode> graph = nodes.findByWarehouseId(warehouseId);
        if (graph.isEmpty() || workplaceId == null) {
            return List.of();
        }
        AutomationTopologyDto model = automation.load(warehouseId);
        PlacedEquipmentDto station = firstMatch(model,
                e -> workplaceId.equals(e.stationId()));
        if (station == null) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(ownPointCodes(model, station, List.of("INDUCT", "DISCHARGE")));
        candidates.addAll(connectedPointCodes(model, station));
        return rankedCandidates(candidates, graph, station);
    }

    /**
     * The routing-graph node where the ASRS hands totes onto the conveyor: the {@code asrs}-category
     * placement, resolved per the class strategy (DISCHARGE/OUT function point first).
     */
    @Transactional(readOnly = true)
    public Optional<String> storageEntryNode(UUID warehouseId) {
        return storageEntryCandidates(warehouseId).stream().findFirst();
    }

    /** ALL plausible storage-side nodes, best first — see {@link #destinationCandidates}. */
    @Transactional(readOnly = true)
    public List<String> storageEntryCandidates(UUID warehouseId) {
        List<ConveyorNode> graph = nodes.findByWarehouseId(warehouseId);
        if (graph.isEmpty()) {
            return List.of();
        }
        AutomationTopologyDto model = automation.load(warehouseId);
        PlacedEquipmentDto asrs = firstMatch(model,
                e -> e.category() != null && "asrs".equals(e.category().trim().toLowerCase()));
        if (asrs == null) {
            return List.of();
        }
        List<String> candidates = new ArrayList<>();
        candidates.addAll(ownPointCodes(model, asrs, List.of("DISCHARGE", "OUT", "FEED")));
        candidates.addAll(connectedPointCodes(model, asrs));
        return rankedCandidates(candidates, graph, asrs);
    }

    /** Cap on the by-distance fallback candidates appended after the named ones. */
    private static final int NEAREST_CAP = 25;

    /**
     * The named candidates that actually projected (original order), followed by every projected node
     * sorted by distance to the anchor (capped), de-duplicated. The dispatcher walks this list pairing
     * entry × destination until the routing graph confirms a path.
     */
    private static List<String> rankedCandidates(List<String> named, List<ConveyorNode> graph,
                                                 PlacedEquipmentDto anchor) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Set<String> codes = new HashSet<>();
        for (ConveyorNode n : graph) {
            codes.add(n.getCode());
        }
        for (String c : named) {
            if (codes.contains(c)) {
                out.add(c);
            }
        }
        double x = num(anchor.posXM());
        double z = num(anchor.posZM());
        graph.stream()
                .filter(n -> n.getPosX() != null && n.getPosY() != null)
                .sorted(Comparator.comparingDouble(
                        n -> Math.hypot(n.getPosX() - x, n.getPosY() - z)))
                .limit(NEAREST_CAP)
                .forEach(n -> out.add(n.getCode()));
        return new ArrayList<>(out);
    }

    // ---- candidate collection -------------------------------------------------------------------

    private static PlacedEquipmentDto firstMatch(AutomationTopologyDto model,
                                                 java.util.function.Predicate<PlacedEquipmentDto> p) {
        if (model.equipment() == null) {
            return null;
        }
        return model.equipment().stream().filter(p).findFirst().orElse(null);
    }

    /** Node codes of function points ON the placement, the listed function types first. */
    private static List<String> ownPointCodes(AutomationTopologyDto model, PlacedEquipmentDto anchor,
                                              List<String> preferredTypes) {
        if (model.functionPoints() == null) {
            return List.of();
        }
        List<FunctionPointDto> own = new ArrayList<>();
        for (FunctionPointDto fp : model.functionPoints()) {
            if (anchor.id().equals(fp.placedId()) && fp.nodeCode() != null && !fp.nodeCode().isBlank()) {
                own.add(fp);
            }
        }
        own.sort(Comparator.comparingInt(fp -> typeRank(fp.functionType(), preferredTypes)));
        return own.stream().map(fp -> fp.nodeCode().trim()).toList();
    }

    private static int typeRank(String functionType, List<String> preferredTypes) {
        if (functionType == null) {
            return preferredTypes.size();
        }
        int idx = preferredTypes.indexOf(functionType.trim().toUpperCase());
        return idx >= 0 ? idx : preferredTypes.size();
    }

    /**
     * Node codes of function points anchored by connections touching the placement — the far-side
     * point of each connection, as {@code RoutingProjectionService.syncStationNodes} resolves them
     * ({@code nodeCode} falling back to the FP name), STOCK before ORDER before unlabelled.
     */
    private static List<String> connectedPointCodes(AutomationTopologyDto model, PlacedEquipmentDto anchor) {
        if (model.connections() == null) {
            return List.of();
        }
        record Candidate(String code, int rank) {
        }
        List<Candidate> out = new ArrayList<>();
        for (ConnectionDto c : model.connections()) {
            UUID farPointId;
            if (anchor.id().equals(c.fromPlacedId())) {
                farPointId = c.toPointId();
            } else if (anchor.id().equals(c.toPlacedId())) {
                farPointId = c.fromPointId();
            } else {
                continue;
            }
            FunctionPointDto fp = pointById(model, farPointId);
            if (fp == null) {
                continue;
            }
            String code = fp.nodeCode() != null && !fp.nodeCode().isBlank() ? fp.nodeCode().trim()
                    : fp.name();
            if (code == null || code.isBlank()) {
                continue;
            }
            String label = c.label() == null ? "" : c.label().trim().toUpperCase();
            int rank = "STOCK".equals(label) ? 0 : "ORDER".equals(label) ? 1 : 2;
            out.add(new Candidate(code.trim(), rank));
        }
        out.sort(Comparator.comparingInt(Candidate::rank));
        return out.stream().map(Candidate::code).toList();
    }

    private static FunctionPointDto pointById(AutomationTopologyDto model, UUID pointId) {
        if (pointId == null || model.functionPoints() == null) {
            return null;
        }
        return model.functionPoints().stream().filter(fp -> pointId.equals(fp.id())).findFirst().orElse(null);
    }

    // ---- resolution against the projected graph -------------------------------------------------

    /** The first candidate code that actually projected as a conveyor node. */
    private static Optional<String> firstProjected(List<String> candidates, List<ConveyorNode> graph) {
        Set<String> codes = new HashSet<>();
        for (ConveyorNode n : graph) {
            codes.add(n.getCode());
        }
        return candidates.stream().filter(codes::contains).findFirst();
    }

    /** Fallback: the projected node nearest to the placement centre ({@code posXM}/{@code posZM}). */
    private static Optional<String> nearestNode(List<ConveyorNode> graph, PlacedEquipmentDto anchor) {
        double x = num(anchor.posXM());
        double z = num(anchor.posZM());
        ConveyorNode best = null;
        double bestDist = Double.MAX_VALUE;
        for (ConveyorNode n : graph) {
            if (n.getPosX() == null || n.getPosY() == null) {
                continue;
            }
            double d = Math.hypot(n.getPosX() - x, n.getPosY() - z);
            if (d < bestDist) {
                bestDist = d;
                best = n;
            }
        }
        return Optional.ofNullable(best).map(ConveyorNode::getCode);
    }

    private static double num(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
