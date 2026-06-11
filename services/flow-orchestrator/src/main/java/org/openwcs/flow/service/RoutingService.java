package org.openwcs.flow.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.RouteView;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorLoop;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.domain.HuRoute;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorLoopRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.openwcs.flow.repo.HuRouteRepository;
import org.openwcs.flow.repo.InductionQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conveyor routing: assign a handling unit a route plan (ordered target nodes) and, on each
 * scan, decide where it goes next — advancing through the plan and computing the next hop toward
 * the current target via {@link RoutingEngine}. (Loop capacity is layered on in a follow-up.)
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;
    private final HuRouteRepository routes;
    private final InductionQueueEntryRepository inductionEntries;
    private final HuTraceService trace;

    public RoutingService(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges,
                          ConveyorLoopRepository loops, HuRouteRepository routes,
                          InductionQueueEntryRepository inductionEntries, HuTraceService trace) {
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
        this.routes = routes;
        this.inductionEntries = inductionEntries;
        this.trace = trace;
    }

    @Transactional
    public RouteView assignRoute(RouteRequest request) {
        HuRoute route = routes
                .findFirstByWarehouseIdAndBarcodeAndStatus(request.warehouseId(), request.barcode(), "ACTIVE")
                .orElseGet(HuRoute::new);
        route.setWarehouseId(request.warehouseId());
        route.setBarcode(request.barcode());
        route.setTargets(request.targets());
        route.setCurrentIndex(0);
        route.setStatus("ACTIVE");
        route.setDetail(null);
        return view(routes.save(route));
    }

    @Transactional(readOnly = true)
    public Optional<RouteView> getRoute(UUID warehouseId, String barcode) {
        return routes.findFirstByWarehouseIdAndBarcodeOrderByCreatedAtDesc(warehouseId, barcode)
                .map(RoutingService::view);
    }

    /**
     * Decide where a scanned handling unit goes next. ADR-0008 §3: every scan that belongs to a
     * live transport (an induction entry exists for the barcode) is appended to the HU transport
     * trace as a {@code SCANNED} row carrying the answer — the trace becomes a true, live
     * material-flow timeline. Unknown barcodes (no entry) are answered but never traced.
     */
    @Transactional
    public RoutingDecision decide(ScanRequest scan) {
        RoutingDecision decision = evaluate(scan);
        recordScan(scan, decision);
        return decision;
    }

    private RoutingDecision evaluate(ScanRequest scan) {
        ConveyorNode here = nodes.findByWarehouseIdAndCode(scan.warehouseId(), scan.node()).orElse(null);
        if (here == null) {
            return RoutingDecision.exception("Unknown node: " + scan.node());
        }
        HuRoute route = routes
                .findFirstByWarehouseIdAndBarcodeAndStatus(scan.warehouseId(), scan.barcode(), "ACTIVE")
                .orElse(null);
        if (route == null) {
            return RoutingDecision.noRoute();
        }
        // The HU is at `here` now; record its loop for occupancy counting.
        route.setCurrentLoop(here.getLoopCode());
        List<String> targets = route.getTargets();

        String reached = null;
        // If we're at the current target, mark it reached and advance to the next.
        if (route.getCurrentIndex() < targets.size()
                && scan.node().equals(targets.get(route.getCurrentIndex()))) {
            reached = scan.node();
            route.setCurrentIndex(route.getCurrentIndex() + 1);
        }
        if (route.getCurrentIndex() >= targets.size()) {
            route.setStatus("COMPLETED");
            routes.save(route);
            return RoutingDecision.complete(reached);
        }

        String currentTarget = targets.get(route.getCurrentIndex());
        ConveyorNode target = nodes.findByWarehouseIdAndCode(scan.warehouseId(), currentTarget).orElse(null);
        if (target == null) {
            return fail(route, "Unknown target node in route plan: " + currentTarget);
        }
        List<ConveyorEdge> warehouseEdges = edges.findByWarehouseId(scan.warehouseId());
        Optional<ConveyorEdge> hopOpt = RoutingEngine.nextHop(warehouseEdges, here.getId(), target.getId());
        if (hopOpt.isEmpty()) {
            return fail(route, "No path from " + scan.node() + " to target " + currentTarget);
        }
        ConveyorEdge hop = hopOpt.get();
        ConveyorNode toNode = nodes.findById(hop.getToNodeId()).orElse(null);
        String toLoop = toNode == null ? null : toNode.getLoopCode();

        // Loop capacity: if this hop would enter a loop the HU isn't already in, and that loop is
        // at its limit, HOLD upstream or divert to the loop's overflow target.
        if (toLoop != null && !toLoop.equals(here.getLoopCode())) {
            // Take a write lock on the loop row so the occupancy count below and the decision to
            // enter are atomic — concurrent scans entering the same loop (across replicas) serialize
            // here, so capacity can't be exceeded by a check-then-act race.
            ConveyorLoop loop = loops.lockByWarehouseIdAndCode(scan.warehouseId(), toLoop).orElse(null);
            if (loop != null
                    && routes.countByWarehouseIdAndCurrentLoopAndStatus(scan.warehouseId(), toLoop, "ACTIVE")
                            >= loop.getMaxHus()) {
                routes.save(route);
                if ("OVERFLOW".equals(loop.getWhenFull()) && loop.getOverflowTargetCode() != null) {
                    RoutingDecision overflow = overflowToward(scan.warehouseId(), warehouseEdges, here,
                            loop.getOverflowTargetCode(), currentTarget);
                    if (overflow != null) {
                        return overflow;
                    }
                }
                return RoutingDecision.hold(currentTarget, "Loop " + toLoop + " is at capacity");
            }
        }

        routes.save(route);
        String toCode = toNode == null ? null : toNode.getCode();
        return RoutingDecision.route(hop.getExitCode(), toCode, currentTarget, reached);
    }

    /**
     * Append a {@code SCANNED} trace row when the barcode belongs to a live transport (the most
     * recent induction entry for the HU code). Fully isolated: a trace failure must NEVER break the
     * routing answer (mirrors how {@link RoutingProjectionService} isolates its station-node side
     * effect), and an unknown barcode (no entry) writes nothing — strays scan all the time.
     */
    private void recordScan(ScanRequest scan, RoutingDecision decision) {
        try {
            inductionEntries
                    .findFirstByWarehouseIdAndHuCodeOrderByRequestedAtDesc(scan.warehouseId(), scan.barcode())
                    .ifPresent(entry -> trace.record(scan.warehouseId(), entry.getHuId(), entry.getHuCode(),
                            scan.node(), "SCANNED", describe(decision), null, decision.toNode(),
                            entry.getWorkplaceId(), null, entry.getId()));
        } catch (Throwable t) {
            log.warn("scan trace failed (ignored) for {} at {}: {}", scan.barcode(), scan.node(), t.toString());
        }
    }

    /** A short human string of the routing answer for the trace's {@code decision} column. */
    private static String describe(RoutingDecision d) {
        return switch (d.action()) {
            case "ROUTE" -> "routed to " + d.toNode() + " via " + d.exitCode();
            case "HOLD" -> "held: loop full";
            case "COMPLETE" -> "destination reached";
            case "NO_ROUTE" -> "no route";
            default -> "exception: " + d.detail();
        };
    }

    private RoutingDecision overflowToward(UUID warehouseId, List<ConveyorEdge> warehouseEdges,
                                           ConveyorNode here, String overflowCode, String currentTarget) {
        ConveyorNode overflow = nodes.findByWarehouseIdAndCode(warehouseId, overflowCode).orElse(null);
        if (overflow == null) {
            return null;
        }
        Optional<ConveyorEdge> hop = RoutingEngine.nextHop(warehouseEdges, here.getId(), overflow.getId());
        if (hop.isEmpty()) {
            return null;
        }
        String toCode = codeOf(warehouseId, hop.get().getToNodeId());
        return RoutingDecision.overflow(hop.get().getExitCode(), toCode, currentTarget,
                "Loop full → diverting to overflow " + overflowCode);
    }

    private RoutingDecision fail(HuRoute route, String detail) {
        log.warn("Routing exception for {}: {}", route.getBarcode(), detail);
        route.setStatus("EXCEPTION");
        route.setDetail(detail);
        routes.save(route);
        return RoutingDecision.exception(detail);
    }

    private String codeOf(UUID warehouseId, UUID nodeId) {
        return nodes.findById(nodeId).map(ConveyorNode::getCode).orElse(null);
    }

    private static RouteView view(HuRoute r) {
        return new RouteView(r.getWarehouseId(), r.getBarcode(), r.getTargets(), r.getCurrentIndex(),
                r.getStatus(), r.getDetail());
    }
}
