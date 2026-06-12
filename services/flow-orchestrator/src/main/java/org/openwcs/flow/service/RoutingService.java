package org.openwcs.flow.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.openwcs.flow.service.RoutingGraphCache.CachedEdge;
import org.openwcs.flow.service.RoutingGraphCache.CachedNode;
import org.openwcs.flow.service.RoutingGraphCache.GraphSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conveyor routing: assign a handling unit a route plan (ordered target nodes) and, on each
 * scan, decide where it goes next — advancing through the plan and computing the next hop toward
 * the current target.
 *
 * <p><b>Hard-real-time fast path</b> (300 scans/s, ~10 ms answer budget): the graph (nodes,
 * adjacency, loop config) comes from the per-warehouse in-memory {@link RoutingGraphCache}
 * snapshot with precomputed next-hop tables — no per-scan edge fetch, no per-scan Dijkstra. The
 * synchronous work per scan is the route-plan lookup (one indexed DB read), the route-position
 * save when a plan exists (one UPDATE), and — only when a hop would ENTER a loop — the loop lock
 * + occupancy count. Counters and trace rows ride the async {@link ScanSideEffects} queue.
 *
 * <p>The route-plan lookup deliberately stays a DB read: flow scales as stateless replicas, and a
 * per-instance route cache would answer from a plan another replica already advanced or replaced.
 * One point read on the {@code (warehouse_id, barcode, status)} index is the price of
 * replica-consistent answers.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** Per-decision latency budget (ADR-0008): adapters expect the answer within ~10 ms. */
    private static final long DECISION_BUDGET_NANOS = 10_000_000L;

    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;
    private final HuRouteRepository routes;
    private final RoutingGraphCache graphCache;
    private final ScanSideEffects sideEffects;
    private final DecisionLatencyTracker latency;
    private final TransactionTemplate decideTx;

    public RoutingService(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges,
                          ConveyorLoopRepository loops, HuRouteRepository routes,
                          RoutingGraphCache graphCache, ScanSideEffects sideEffects,
                          DecisionLatencyTracker latency,
                          PlatformTransactionManager transactionManager) {
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
        this.routes = routes;
        this.graphCache = graphCache;
        this.sideEffects = sideEffects;
        this.latency = latency;
        this.decideTx = new TransactionTemplate(transactionManager);
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
        log.info("route plan assigned for hu {}: targets {} ({})", request.barcode(), request.targets(),
                route.getId() == null ? "new plan" : "replacing the active plan");
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
     * material-flow timeline. Unknown barcodes (no entry) are answered but never traced. Trace
     * rows and reporting counters are persisted ASYNCHRONOUSLY by {@link ScanSideEffects}.
     *
     * <p>Hard-real-time path: conveyor adapters expect the answer within ~10 ms per scan. The whole
     * decision is timed end to end with {@link System#nanoTime()} (the routing transaction runs via
     * {@link TransactionTemplate} INSIDE the measured window, so commit time is included), recorded
     * in the {@link DecisionLatencyTracker} ring buffer, and any single decision over budget WARNs
     * with its per-phase breakdown so a slow site is diagnosable from the log alone.
     */
    public RoutingDecision decide(ScanRequest scan) {
        long start = System.nanoTime();
        ScanFlags flags = new ScanFlags();
        RoutingDecision decision = decideTx.execute(status -> evaluate(scan, flags));
        long afterRouting = System.nanoTime();
        boolean noRead = isReadError(scan.barcode());
        sideEffects.enqueue(scan.warehouseId(), scan.node(), noRead ? null : scan.barcode(), noRead,
                flags.unknownBarcode, decision);
        long end = System.nanoTime();

        long total = end - start;
        latency.record(total);
        if (total > DECISION_BUDGET_NANOS) {
            log.warn("slow routing decision for barcode {} at node {}: {} took {} ms (budget 10 ms; "
                            + "adapters may fall back to the divert default) — breakdown: routing+commit "
                            + "{} ms, side-effect enqueue {} ms",
                    scan.barcode(), scan.node(), decision.action(), ms(total),
                    ms(afterRouting - start), ms(end - afterRouting));
        }
        return decision;
    }

    /** Nanos as milliseconds with two decimals, for the slow-decision log line. */
    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }

    /** Per-scan facts {@code evaluate} learns that the decision alone doesn't carry (counters). */
    private static final class ScanFlags {
        /** The barcode read fine but no active route plan exists for it. */
        boolean unknownBarcode;
    }

    private RoutingDecision evaluate(ScanRequest scan, ScanFlags flags) {
        GraphSnapshot graph = graphCache.get(scan.warehouseId());
        CachedNode here = graph.node(scan.node());
        if (here == null) {
            log.warn("scan of barcode {} at unknown node {}: answering EXCEPTION (node not in the routing graph)",
                    scan.barcode(), scan.node());
            return RoutingDecision.exception("Unknown node: " + scan.node());
        }
        if (isReadError(scan.barcode())) {
            // Scanner read error: the barcode is unknown, so routing cannot know where this tote
            // should go. Same answer as an unrouted HU: divert default, the only exit, or stop.
            log.info("read error at node {} (barcode '{}'): following the divert default if any",
                    scan.node(), scan.barcode() == null ? "" : scan.barcode());
            RoutingDecision noRead = decideWithoutPath(graph, scan, here, "barcode read error");
            if (noRead != null) {
                return noRead;
            }
            log.warn("read error at node {} and no default/only exit: answering NO_ROUTE (tote stops)",
                    scan.node());
            return RoutingDecision.noRoute();
        }
        // Deliberately a DB read, NOT cached: route plans are per-tote dynamic state shared across
        // the horizontally-scaled replicas (any replica may assign/advance a plan); one point read
        // on the (warehouse_id, barcode, status) index keeps every replica's answer consistent.
        HuRoute route = routes
                .findFirstByWarehouseIdAndBarcodeAndStatus(scan.warehouseId(), scan.barcode(), "ACTIVE")
                .orElse(null);
        if (route == null) {
            // No plan steering this HU (unknown HU or no route plan assigned): follow the divert
            // default / the only exit, or stop at a decision point. Every scan asks fresh, so a
            // plan assigned mid-journey takes over at the very next scan.
            flags.unknownBarcode = true;
            RoutingDecision unplanned = decideWithoutPath(graph, scan, here, "no route plan");
            if (unplanned != null) {
                return unplanned;
            }
            // Strays scan all the time; per-scan detail stays DEBUG, anomalies that change state are louder.
            log.debug("scan of barcode {} at node {}: no active route plan, answering NO_ROUTE",
                    scan.barcode(), scan.node());
            return RoutingDecision.noRoute();
        }
        // The HU is at `here` now; record its loop for occupancy counting.
        route.setCurrentLoop(here.loopCode());
        List<String> targets = route.getTargets();

        String reached = null;
        // If we're at the current target, mark it reached and advance to the next.
        if (route.getCurrentIndex() < targets.size()
                && scan.node().equals(targets.get(route.getCurrentIndex()))) {
            reached = scan.node();
            route.setCurrentIndex(route.getCurrentIndex() + 1);
            log.info("hu {} reached target {} ({} of {} targets done)", scan.barcode(), reached,
                    route.getCurrentIndex(), targets.size());
        }
        if (route.getCurrentIndex() >= targets.size()) {
            route.setStatus("COMPLETED");
            routes.save(route);
            log.info("route for hu {} COMPLETED at node {}: all {} targets reached", scan.barcode(),
                    scan.node(), targets.size());
            return RoutingDecision.complete(reached);
        }

        String currentTarget = targets.get(route.getCurrentIndex());
        CachedNode target = graph.node(currentTarget);
        if (target == null) {
            return fail(route, "Unknown target node in route plan: " + currentTarget);
        }
        CachedEdge hop = graph.nextHop(here.id(), target.id());
        if (hop == null) {
            // The plan cannot be satisfied from HERE (physical situation: blocked/one-way layout).
            // Adapt instead of failing: follow the divert default / the only exit (the plan stays
            // ACTIVE and is re-evaluated at the next scan), or stop at a decision point.
            RoutingDecision adapted = decideWithoutPath(graph, scan, here,
                    "no path to target " + currentTarget);
            if (adapted != null) {
                routes.save(route);
                return adapted;
            }
            return fail(route, "No path from " + scan.node() + " to target " + currentTarget);
        }
        String toLoop = hop.toLoopCode();

        // Loop capacity: if this hop would enter a loop the HU isn't already in, and that loop is
        // at its limit, HOLD upstream or divert to the loop's overflow target. The loop's EXISTENCE
        // comes from the snapshot (no DB touch on a non-loop hop, the overwhelmingly common case);
        // the lock + occupancy COUNT must stay in the DB — occupancy is dynamic state shared across
        // replicas, and the row lock is what serialises concurrent entries (cross-replica) so
        // maxHus can't be exceeded by a check-then-act race.
        if (toLoop != null && !toLoop.equals(here.loopCode()) && graph.loop(toLoop) != null) {
            // Take a write lock on the loop row so the occupancy count below and the decision to
            // enter are atomic — concurrent scans entering the same loop (across replicas) serialize
            // here, so capacity can't be exceeded by a check-then-act race.
            ConveyorLoop loop = loops.lockByWarehouseIdAndCode(scan.warehouseId(), toLoop).orElse(null);
            if (loop != null
                    && routes.countByWarehouseIdAndCurrentLoopAndStatus(scan.warehouseId(), toLoop, "ACTIVE")
                            >= loop.getMaxHus()) {
                routes.save(route);
                if ("OVERFLOW".equals(loop.getWhenFull()) && loop.getOverflowTargetCode() != null) {
                    RoutingDecision overflow = overflowToward(graph, here,
                            loop.getOverflowTargetCode(), currentTarget);
                    if (overflow != null) {
                        log.warn("loop {} at capacity ({} HUs): diverting hu {} at node {} to overflow {} "
                                        + "via exit {} (loop policy OVERFLOW, original target {})",
                                toLoop, loop.getMaxHus(), scan.barcode(), scan.node(),
                                loop.getOverflowTargetCode(), overflow.exitCode(), currentTarget);
                        return overflow;
                    }
                }
                log.warn("loop {} at capacity ({} HUs): holding hu {} at node {} (loop policy {}, "
                                + "target {} unreachable until a slot frees)",
                        toLoop, loop.getMaxHus(), scan.barcode(), scan.node(), loop.getWhenFull(),
                        currentTarget);
                return RoutingDecision.hold(currentTarget, "Loop " + toLoop + " is at capacity");
            }
        }

        // The route-position save stays SYNCHRONOUS on purpose (one UPDATE by primary key in the
        // already-open decision transaction). Making it async would break correctness, not just
        // freshness: (a) loop capacity counts ACTIVE routes by current_loop under the loop row
        // lock above — a lagging position write would let a tote slip into a full loop before its
        // occupancy is visible; (b) currentIndex/status progression IS the next scan's input for
        // multi-target plans (and the COMPLETE transition), on whichever replica answers it.
        routes.save(route);
        log.info("next hop for hu {} at node {}: edge to {} via exit {} (shortest path to target {}, "
                + "edge cost {})", scan.barcode(), scan.node(), hop.toCode(), hop.exitCode(), currentTarget,
                hop.cost());
        return RoutingDecision.route(hop.exitCode(), hop.toCode(), currentTarget, reached);
    }

    /**
     * What a handling unit does at {@code here} when no route plan can steer it (no plan at all, or
     * the plan has no path from this node). Routing is per-scan and must adapt to the physical
     * situation, so:
     * <ul>
     *   <li>the node carries a divert default (set in the topology screen) → ROUTE to it;</li>
     *   <li>exactly one out-edge (a plain conveyor segment) → ROUTE along it, a conveyor never
     *       strands a tote mid-belt;</li>
     *   <li>several out-edges but no default (a real decision point) → HOLD, the tote stops at the
     *       divert and is re-evaluated on the next scan (a plan assigned meanwhile takes over);</li>
     *   <li>no out-edges → null, the caller keeps its existing dead-end answer.</li>
     * </ul>
     */
    private RoutingDecision decideWithoutPath(GraphSnapshot graph, ScanRequest scan, CachedNode here,
                                              String reason) {
        List<CachedEdge> out = graph.outEdges(here.id());
        if (out.isEmpty()) {
            return null;
        }
        if (here.defaultExitCode() != null) {
            for (CachedEdge e : out) {
                if (here.defaultExitCode().equals(e.toCode())) {
                    log.info("hu {} at divert {}: {}, following divert default to {} via exit {}",
                            scan.barcode(), scan.node(), reason, e.toCode(), e.exitCode());
                    return RoutingDecision.routeByDefault(e.exitCode(), e.toCode(),
                            capitalise(reason) + ": following divert default to " + e.toCode());
                }
            }
            log.warn("hu {} at divert {}: default exit {} has no matching out-edge (stale projection?)",
                    scan.barcode(), scan.node(), here.defaultExitCode());
        }
        if (out.size() == 1) {
            CachedEdge only = out.get(0);
            log.debug("hu {} at node {}: {}, continuing along the only exit to {}",
                    scan.barcode(), scan.node(), reason, only.toCode());
            return RoutingDecision.routeByDefault(only.exitCode(), only.toCode(),
                    capitalise(reason) + ": continuing along the only exit to " + only.toCode());
        }
        log.info("hu {} stops at divert {}: {} and no default direction is configured ({} exits)",
                scan.barcode(), scan.node(), reason, out.size());
        return RoutingDecision.hold(null,
                capitalise(reason) + " and no default at divert " + scan.node() + ": tote stops");
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Blank or "NOREAD" means the scanner failed to read a barcode. */
    private static boolean isReadError(String barcode) {
        return barcode == null || barcode.isBlank() || "NOREAD".equalsIgnoreCase(barcode.trim());
    }

    private RoutingDecision overflowToward(GraphSnapshot graph, CachedNode here, String overflowCode,
                                           String currentTarget) {
        CachedNode overflow = graph.node(overflowCode);
        if (overflow == null) {
            return null;
        }
        CachedEdge hop = graph.nextHop(here.id(), overflow.id());
        if (hop == null) {
            return null;
        }
        return RoutingDecision.overflow(hop.exitCode(), hop.toCode(), currentTarget,
                "Loop full → diverting to overflow " + overflowCode);
    }

    private RoutingDecision fail(HuRoute route, String detail) {
        log.warn("routing exception for hu {}: {} (route set to EXCEPTION, manual intervention needed)",
                route.getBarcode(), detail);
        route.setStatus("EXCEPTION");
        route.setDetail(detail);
        routes.save(route);
        return RoutingDecision.exception(detail);
    }

    private static RouteView view(HuRoute r) {
        return new RouteView(r.getWarehouseId(), r.getBarcode(), r.getTargets(), r.getCurrentIndex(),
                r.getStatus(), r.getDetail());
    }

    /**
     * A reusable reachability oracle over the warehouse's projected graph (ADR-0008 §1 follow-up):
     * loads nodes + edges ONCE and answers {@code exists(fromCode, toCode)} by BFS over the directed
     * edges. The dispatcher uses it to pair transport endpoints — an entry node that cannot reach the
     * destination (e.g. a dead-end inbound stub like {@code ASRS-1#106}) must never be assigned, or
     * the live walk fails its first scan with "no path".
     */
    @Transactional(readOnly = true)
    public PathChecker pathChecker(UUID warehouseId) {
        Map<String, UUID> idByCode = new HashMap<>();
        for (ConveyorNode n : nodes.findByWarehouseId(warehouseId)) {
            idByCode.put(n.getCode(), n.getId());
        }
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (ConveyorEdge e : edges.findByWarehouseId(warehouseId)) {
            adjacency.computeIfAbsent(e.getFromNodeId(), k -> new ArrayList<>()).add(e.getToNodeId());
        }
        return (fromCode, toCode) -> {
            UUID from = idByCode.get(fromCode);
            UUID to = idByCode.get(toCode);
            if (from == null || to == null) {
                return false;
            }
            if (from.equals(to)) {
                return true;
            }
            Set<UUID> seen = new HashSet<>();
            Deque<UUID> queue = new ArrayDeque<>();
            seen.add(from);
            queue.add(from);
            while (!queue.isEmpty()) {
                UUID cur = queue.poll();
                for (UUID next : adjacency.getOrDefault(cur, List.of())) {
                    if (next.equals(to)) {
                        return true;
                    }
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return false;
        };
    }

    /** Directed reachability between two node CODES on a warehouse's projected graph. */
    @FunctionalInterface
    public interface PathChecker {
        boolean exists(String fromCode, String toCode);
    }
}
