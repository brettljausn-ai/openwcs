package org.openwcs.flow.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorLoop;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorLoopRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Per-warehouse in-memory snapshot of the conveyor routing graph, for the hard-real-time scan
 * path. The graph (nodes with their loop/default-exit config, adjacency lists, loop configs) is
 * read from the DB ONCE per warehouse, kept as an immutable {@link GraphSnapshot}, and served to
 * every scan; without it each scan re-fetched the FULL warehouse edge set and ran Dijkstra from
 * scratch.
 *
 * <p><b>Next-hop tables:</b> the first scan that needs a path to target T computes the
 * reverse-Dijkstra next-hop map for T once (every node's first edge on a least-cost path to T)
 * and memoises it inside the snapshot; subsequent scans toward T are a single map lookup.
 *
 * <p><b>Invalidation:</b> the graph is only written by full-replace operations —
 * {@link TopologyService#replace} (editor PUT) and {@link RoutingProjectionService}'s persist
 * (topology projection). Both call {@link #evictAfterCommit} so the next scan rebuilds from the
 * committed graph. Defensively, a snapshot older than {@link #TTL} is rebuilt anyway, so a missed
 * invalidation (a future write path, or a write on ANOTHER replica — the cache is per instance)
 * self-heals within a minute. Route plans and loop OCCUPANCY are deliberately NOT cached: they
 * are per-tote dynamic state shared across replicas and stay in the DB.
 */
@Service
public class RoutingGraphCache {

    private static final Logger log = LoggerFactory.getLogger(RoutingGraphCache.class);

    /** Defensive rebuild interval: a stale snapshot (missed/foreign-replica write) heals itself. */
    static final Duration TTL = Duration.ofSeconds(60);

    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;

    private final ConcurrentHashMap<UUID, GraphSnapshot> byWarehouse = new ConcurrentHashMap<>();

    public RoutingGraphCache(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges,
                             ConveyorLoopRepository loops) {
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
    }

    /** The warehouse's current snapshot, built from the DB on first use / after eviction / TTL. */
    public GraphSnapshot get(UUID warehouseId) {
        GraphSnapshot cached = byWarehouse.get(warehouseId);
        if (cached != null && !cached.expired()) {
            return cached;
        }
        return byWarehouse.compute(warehouseId, (id, current) ->
                current != null && !current.expired() ? current : build(id));
    }

    /** Drop the warehouse's snapshot now; the next scan rebuilds from the DB. */
    public void evict(UUID warehouseId) {
        byWarehouse.remove(warehouseId);
        log.debug("routing graph snapshot evicted for warehouse {}", warehouseId);
    }

    /**
     * Evict once the surrounding transaction COMMITS (the graph writers replace the whole graph
     * inside one transaction; evicting mid-transaction would let a concurrent scan rebuild from
     * the not-yet-visible old rows and cache them past the commit). Outside a transaction this
     * evicts immediately.
     */
    public void evictAfterCommit(UUID warehouseId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(warehouseId);
                }
            });
        } else {
            evict(warehouseId);
        }
    }

    private GraphSnapshot build(UUID warehouseId) {
        long start = System.nanoTime();
        List<ConveyorNode> nodeRows = nodes.findByWarehouseId(warehouseId);
        List<ConveyorEdge> edgeRows = edges.findByWarehouseId(warehouseId);
        List<ConveyorLoop> loopRows = loops.findByWarehouseId(warehouseId);

        Map<UUID, CachedNode> byId = new HashMap<>();
        Map<String, CachedNode> byCode = new HashMap<>();
        for (ConveyorNode n : nodeRows) {
            CachedNode cached = new CachedNode(n.getId(), n.getCode(), n.getLoopCode(), n.getDefaultExitCode());
            byId.put(cached.id(), cached);
            byCode.put(cached.code(), cached);
        }
        Map<UUID, List<CachedEdge>> out = new HashMap<>();
        Map<UUID, List<CachedEdge>> in = new HashMap<>();
        for (ConveyorEdge e : edgeRows) {
            CachedNode to = byId.get(e.getToNodeId());
            CachedEdge cached = new CachedEdge(e.getFromNodeId(), e.getToNodeId(),
                    to == null ? null : to.code(), to == null ? null : to.loopCode(),
                    e.getExitCode(), e.getCost());
            out.computeIfAbsent(cached.fromNodeId(), k -> new ArrayList<>()).add(cached);
            in.computeIfAbsent(cached.toNodeId(), k -> new ArrayList<>()).add(cached);
        }
        Map<String, CachedLoop> loopByCode = new HashMap<>();
        for (ConveyorLoop l : loopRows) {
            loopByCode.put(l.getCode(), new CachedLoop(l.getCode(), l.getMaxHus(), l.getWhenFull(),
                    l.getOverflowTargetCode()));
        }
        GraphSnapshot snapshot = new GraphSnapshot(byCode, byId, out, in, loopByCode);
        log.info("routing graph snapshot built for warehouse {}: {} nodes, {} edges, {} loops in {} µs "
                        + "(cached for routing; next-hop tables computed per target on demand)",
                warehouseId, nodeRows.size(), edgeRows.size(), loopRows.size(),
                (System.nanoTime() - start) / 1_000);
        return snapshot;
    }

    /** A node as the scan path needs it: identity plus loop membership and divert default. */
    public record CachedNode(UUID id, String code, String loopCode, String defaultExitCode) {
    }

    /** A directed edge with its destination's code/loop denormalised (no per-scan node lookups). */
    public record CachedEdge(UUID fromNodeId, UUID toNodeId, String toCode, String toLoopCode,
                             String exitCode, int cost) {
    }

    /** Loop CONFIG only — occupancy is dynamic, shared across replicas, and stays in the DB. */
    public record CachedLoop(String code, int maxHus, String whenFull, String overflowTargetCode) {
    }

    /**
     * An immutable view of one warehouse's routing graph plus per-target next-hop tables that
     * fill in lazily. Replaced wholesale on invalidation — scans never see a half-updated graph.
     */
    public static final class GraphSnapshot {

        private final long builtAtNanos = System.nanoTime();
        private final Map<String, CachedNode> nodesByCode;
        private final Map<UUID, CachedNode> nodesById;
        private final Map<UUID, List<CachedEdge>> outEdges;
        private final Map<UUID, List<CachedEdge>> inEdges;
        private final Map<String, CachedLoop> loopsByCode;
        /** target node id -> (from node id -> first edge of a least-cost path to the target). */
        private final ConcurrentHashMap<UUID, Map<UUID, CachedEdge>> nextHopByTarget =
                new ConcurrentHashMap<>();

        private GraphSnapshot(Map<String, CachedNode> nodesByCode, Map<UUID, CachedNode> nodesById,
                              Map<UUID, List<CachedEdge>> outEdges, Map<UUID, List<CachedEdge>> inEdges,
                              Map<String, CachedLoop> loopsByCode) {
            this.nodesByCode = Map.copyOf(nodesByCode);
            this.nodesById = Map.copyOf(nodesById);
            this.outEdges = Map.copyOf(outEdges);
            this.inEdges = Map.copyOf(inEdges);
            this.loopsByCode = Map.copyOf(loopsByCode);
        }

        boolean expired() {
            return System.nanoTime() - builtAtNanos > TTL.toNanos();
        }

        public CachedNode node(String code) {
            return nodesByCode.get(code);
        }

        public List<CachedEdge> outEdges(UUID nodeId) {
            return outEdges.getOrDefault(nodeId, List.of());
        }

        public CachedLoop loop(String code) {
            return loopsByCode.get(code);
        }

        /**
         * The next edge to traverse from {@code fromNodeId} toward {@code targetNodeId} on a
         * least-cost path, or null when unreachable (or already there). O(1) after the first call
         * per target: the whole next-hop table for the target is memoised.
         */
        public CachedEdge nextHop(UUID fromNodeId, UUID targetNodeId) {
            if (fromNodeId.equals(targetNodeId)) {
                return null;
            }
            return nextHopByTarget.computeIfAbsent(targetNodeId, this::nextHopsToward).get(fromNodeId);
        }

        /**
         * Reverse Dijkstra from the target over the incoming edges: computes, for EVERY node that
         * can reach the target, the first edge of a least-cost path. Equivalent to running the old
         * per-scan forward Dijkstra from each node, paid once per (warehouse graph, target).
         */
        private Map<UUID, CachedEdge> nextHopsToward(UUID targetNodeId) {
            Map<UUID, Long> dist = new HashMap<>();
            Map<UUID, CachedEdge> next = new HashMap<>();
            PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
            // The queue holds {distance, index into discovered}; UUIDs don't fit in a long[] so we
            // keep a side list of discovered nodes.
            List<UUID> discovered = new ArrayList<>();
            dist.put(targetNodeId, 0L);
            discovered.add(targetNodeId);
            queue.add(new long[] {0, 0});
            while (!queue.isEmpty()) {
                long[] head = queue.poll();
                UUID u = discovered.get((int) head[1]);
                long du = dist.get(u);
                if (head[0] > du) {
                    continue; // stale queue entry
                }
                for (CachedEdge e : inEdges.getOrDefault(u, List.of())) {
                    UUID v = e.fromNodeId();
                    long nd = du + Math.max(0, e.cost());
                    Long known = dist.get(v);
                    if (known == null || nd < known) {
                        dist.put(v, nd);
                        next.put(v, e);
                        discovered.add(v);
                        queue.add(new long[] {nd, discovered.size() - 1});
                    }
                }
            }
            next.remove(targetNodeId); // a tote AT the target needs no hop
            return Map.copyOf(next);
        }
    }
}
