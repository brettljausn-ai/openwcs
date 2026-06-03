package org.openwcs.flow.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorEdge;

/**
 * Pure shortest-path routing over the conveyor topology. Given the edges of a warehouse, the
 * current node and a target node, returns the <b>next hop</b> — the edge to leave the current
 * node by (and thus the exit/decision the hardware should apply) on a least-cost path to the
 * target. Recomputed per scan, so adding/removing segments reroutes automatically.
 */
public final class RoutingEngine {

    private RoutingEngine() {
    }

    /** The next edge to traverse from {@code fromNodeId} toward {@code targetNodeId}, if reachable. */
    public static Optional<ConveyorEdge> nextHop(List<ConveyorEdge> edges, UUID fromNodeId, UUID targetNodeId) {
        if (fromNodeId.equals(targetNodeId)) {
            return Optional.empty();
        }
        Map<UUID, List<ConveyorEdge>> adjacency = new HashMap<>();
        for (ConveyorEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFromNodeId(), k -> new ArrayList<>()).add(edge);
        }

        Map<UUID, Long> dist = new HashMap<>();
        Map<UUID, ConveyorEdge> prevEdge = new HashMap<>();
        PriorityQueue<UUID> queue = new PriorityQueue<>(Comparator.comparingLong(n -> dist.getOrDefault(n, Long.MAX_VALUE)));
        dist.put(fromNodeId, 0L);
        queue.add(fromNodeId);

        while (!queue.isEmpty()) {
            UUID u = queue.poll();
            if (u.equals(targetNodeId)) {
                break;
            }
            long du = dist.getOrDefault(u, Long.MAX_VALUE);
            for (ConveyorEdge edge : adjacency.getOrDefault(u, List.of())) {
                long nd = du + Math.max(0, edge.getCost());
                if (nd < dist.getOrDefault(edge.getToNodeId(), Long.MAX_VALUE)) {
                    dist.put(edge.getToNodeId(), nd);
                    prevEdge.put(edge.getToNodeId(), edge);
                    queue.add(edge.getToNodeId());
                }
            }
        }

        if (!prevEdge.containsKey(targetNodeId)) {
            return Optional.empty(); // unreachable
        }
        // Walk predecessors back from target to source, collecting the path edges.
        Deque<ConveyorEdge> path = new ArrayDeque<>();
        UUID cursor = targetNodeId;
        while (!cursor.equals(fromNodeId)) {
            ConveyorEdge edge = prevEdge.get(cursor);
            if (edge == null) {
                return Optional.empty();
            }
            path.push(edge);
            cursor = edge.getFromNodeId();
        }
        return Optional.of(path.peekFirst()); // the edge leaving fromNodeId
    }
}
