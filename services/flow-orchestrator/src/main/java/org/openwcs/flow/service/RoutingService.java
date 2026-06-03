package org.openwcs.flow.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.RouteView;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.domain.HuRoute;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.openwcs.flow.repo.HuRouteRepository;
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
    private final HuRouteRepository routes;

    public RoutingService(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges, HuRouteRepository routes) {
        this.nodes = nodes;
        this.edges = edges;
        this.routes = routes;
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

    /** Decide where a scanned handling unit goes next. */
    @Transactional
    public RoutingDecision decide(ScanRequest scan) {
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
        Optional<ConveyorEdge> hop = RoutingEngine.nextHop(warehouseEdges, here.getId(), target.getId());
        if (hop.isEmpty()) {
            return fail(route, "No path from " + scan.node() + " to target " + currentTarget);
        }
        routes.save(route);
        String toCode = codeOf(scan.warehouseId(), hop.get().getToNodeId());
        return RoutingDecision.route(hop.get().getExitCode(), toCode, currentTarget, reached);
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
