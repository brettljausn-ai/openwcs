package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.service.RoutingEngine;

/** Pure shortest-path next-hop tests for the conveyor routing engine (no Spring/DB). */
class RoutingEngineTest {

    private static ConveyorEdge edge(UUID from, UUID to, String exit, int cost) {
        ConveyorEdge e = new ConveyorEdge();
        e.setFromNodeId(from);
        e.setToNodeId(to);
        e.setExitCode(exit);
        e.setCost(cost);
        return e;
    }

    @Test
    void picksTheCheaperPathsFirstHop() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // A→B→C (cost 2) is cheaper than the direct A→C (cost 5), so the next hop from A is A→B.
        List<ConveyorEdge> edges = List.of(
                edge(a, b, "straight", 1),
                edge(b, c, "straight", 1),
                edge(a, c, "express", 5));

        ConveyorEdge hop = RoutingEngine.nextHop(edges, a, c).orElseThrow();
        assertThat(hop.getExitCode()).isEqualTo("straight");
        assertThat(hop.getToNodeId()).isEqualTo(b);
    }

    @Test
    void takesDirectEdgeWhenCheapest() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<ConveyorEdge> edges = List.of(
                edge(a, b, "straight", 10),
                edge(b, c, "straight", 10),
                edge(a, c, "express", 1));

        ConveyorEdge hop = RoutingEngine.nextHop(edges, a, c).orElseThrow();
        assertThat(hop.getExitCode()).isEqualTo("express");
        assertThat(hop.getToNodeId()).isEqualTo(c);
    }

    @Test
    void unreachableTargetYieldsNoHop() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Only A→B exists; B→A does not.
        assertThat(RoutingEngine.nextHop(List.of(edge(a, b, "s", 1)), b, a)).isEmpty();
    }
}
