package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.LoopDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.service.RoutingService;
import org.openwcs.flow.service.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots flow-orchestrator against PostgreSQL 16. Exercises conveyor routing: walking a handling
 * unit through its targets via the graph, and loop capacity (HOLD / OVERFLOW when a loop is full).
 */
@SpringBootTest
@Testcontainers
class RoutingServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    TopologyService topology;

    @Autowired
    RoutingService routing;

    private static NodeDto node(String code) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, null);
    }

    private static NodeDto loopNode(String code, String loop) {
        return new NodeDto(code, code, null, 0d, 0d, loop, null, null, null);
    }

    private static NodeDto divertNode(String code, String defaultExitCode) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, defaultExitCode);
    }

    // INDUCT → DIVERT → {PACK, SHIP}: DIVERT is a decision point, optionally with a default exit.
    private void divertTopology(UUID wh, String defaultExitCode, NodeDto... extraNodes) {
        List<NodeDto> nodes = new java.util.ArrayList<>(List.of(
                node("INDUCT"), divertNode("DIVERT", defaultExitCode), node("PACK"), node("SHIP")));
        nodes.addAll(List.of(extraNodes));
        topology.replace(wh, new Topology(
                nodes,
                List.of(
                        new EdgeDto("INDUCT", "DIVERT", "straight", 1),
                        new EdgeDto("DIVERT", "PACK", "divert", 1),
                        new EdgeDto("DIVERT", "SHIP", "bypass", 1)),
                List.of(), List.of()));
    }

    @Test
    void routesAHandlingUnitThroughItsTargetsInSequence() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(node("INDUCT"), node("DIVERT"), node("PACK"), node("SHIP")),
                List.of(
                        new EdgeDto("INDUCT", "DIVERT", "straight", 1),
                        new EdgeDto("DIVERT", "PACK", "divert", 1),
                        new EdgeDto("DIVERT", "SHIP", "bypass", 1),
                        new EdgeDto("PACK", "SHIP", "straight", 1)),
                List.of(), List.of()));

        routing.assignRoute(new RouteRequest(wh, "HU1", List.of("PACK", "SHIP")));

        RoutingDecision d1 = routing.decide(new ScanRequest(wh, "INDUCT", "HU1"));
        assertThat(d1.action()).isEqualTo("ROUTE");
        assertThat(d1.currentTarget()).isEqualTo("PACK");
        assertThat(d1.exitCode()).isEqualTo("straight");
        assertThat(d1.toNode()).isEqualTo("DIVERT");

        RoutingDecision d2 = routing.decide(new ScanRequest(wh, "DIVERT", "HU1"));
        assertThat(d2.exitCode()).isEqualTo("divert");
        assertThat(d2.toNode()).isEqualTo("PACK");

        RoutingDecision d3 = routing.decide(new ScanRequest(wh, "PACK", "HU1"));
        assertThat(d3.action()).isEqualTo("ROUTE");
        assertThat(d3.targetReached()).isEqualTo("PACK");
        assertThat(d3.currentTarget()).isEqualTo("SHIP");
        assertThat(d3.toNode()).isEqualTo("SHIP");

        RoutingDecision d4 = routing.decide(new ScanRequest(wh, "SHIP", "HU1"));
        assertThat(d4.action()).isEqualTo("COMPLETE");
        assertThat(d4.targetReached()).isEqualTo("SHIP");

        assertThat(routing.getRoute(wh, "HU1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void unknownBarcodeHasNoRoute() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(List.of(node("N1")), List.of(), List.of(), List.of()));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "N1", "GHOST"));
        assertThat(d.action()).isEqualTo("NO_ROUTE");
    }

    @Test
    void unroutedHuFollowsTheDivertDefault() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh, "SHIP");

        // No route plan exists for FREE-1: at the divert it takes the configured default exit.
        RoutingDecision d = routing.decide(new ScanRequest(wh, "DIVERT", "FREE-1"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("SHIP");
        assertThat(d.exitCode()).isEqualTo("bypass");
        assertThat(d.detail()).contains("divert default");
    }

    @Test
    void unroutedHuContinuesAlongASingleExitNode() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh, null);

        // INDUCT has exactly one out-edge: a plain conveyor segment never strands a tote.
        RoutingDecision d = routing.decide(new ScanRequest(wh, "INDUCT", "FREE-2"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("DIVERT");
        assertThat(d.exitCode()).isEqualTo("straight");
    }

    @Test
    void unroutedHuStopsAtADivertWithoutADefault() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh, null);

        // A real decision point with no plan and no default: the tote stops (HOLD, rescanned).
        RoutingDecision d = routing.decide(new ScanRequest(wh, "DIVERT", "FREE-3"));
        assertThat(d.action()).isEqualTo("HOLD");
        assertThat(d.detail()).contains("no default at divert DIVERT");
    }

    @Test
    void routedHuIgnoresTheDivertDefault() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh, "SHIP");

        // The plan says PACK; the default (SHIP) must not interfere.
        routing.assignRoute(new RouteRequest(wh, "HU-PLAN", List.of("PACK")));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "DIVERT", "HU-PLAN"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("PACK");
        assertThat(d.exitCode()).isEqualTo("divert");
    }

    @Test
    void huAssignedAPlanMidWalkFollowsItAtTheNextScan() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh, "SHIP");

        // First scan: no plan yet, the HU rides the conveyor (single exit at INDUCT).
        RoutingDecision before = routing.decide(new ScanRequest(wh, "INDUCT", "HU-LATE"));
        assertThat(before.action()).isEqualTo("ROUTE");
        assertThat(before.toNode()).isEqualTo("DIVERT");

        // A plan arrives mid-journey: the very next scan follows it instead of the default.
        routing.assignRoute(new RouteRequest(wh, "HU-LATE", List.of("PACK")));
        RoutingDecision after = routing.decide(new ScanRequest(wh, "DIVERT", "HU-LATE"));
        assertThat(after.action()).isEqualTo("ROUTE");
        assertThat(after.toNode()).isEqualTo("PACK");
        assertThat(after.currentTarget()).isEqualTo("PACK");
    }

    @Test
    void routedHuWithoutAPathFollowsTheDefaultAndStaysActive() {
        UUID wh = UUID.randomUUID();
        // ISLAND exists but is unreachable: the plan cannot be satisfied from the divert.
        divertTopology(wh, "SHIP", node("ISLAND"));

        routing.assignRoute(new RouteRequest(wh, "HU-ADAPT", List.of("ISLAND")));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "DIVERT", "HU-ADAPT"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("SHIP");
        assertThat(d.detail()).contains("divert default");
        // The plan is NOT failed: it stays ACTIVE and is re-evaluated at every scan.
        assertThat(routing.getRoute(wh, "HU-ADAPT").orElseThrow().status()).isEqualTo("ACTIVE");
    }

    // ENTRY → [L1 ⇄ L2] (loop) → EXIT, with an overflow branch ENTRY → BUFFER.
    private void loopTopology(UUID wh, String whenFull, String overflowTarget) {
        topology.replace(wh, new Topology(
                List.of(node("ENTRY"), loopNode("L1", "LOOP1"), loopNode("L2", "LOOP1"),
                        node("EXIT"), node("BUFFER")),
                List.of(
                        new EdgeDto("ENTRY", "L1", "in", 1),
                        new EdgeDto("L1", "L2", "straight", 1),
                        new EdgeDto("L2", "EXIT", "out", 1),
                        new EdgeDto("L2", "L1", "recirc", 1),
                        new EdgeDto("ENTRY", "BUFFER", "to_buffer", 1)),
                List.of(new LoopDto("LOOP1", 1, whenFull, overflowTarget)), List.of()));
    }

    @Test
    void holdsWhenTheLoopIsFull() {
        UUID wh = UUID.randomUUID();
        loopTopology(wh, "HOLD", null);

        // HU_A enters the loop (scanned at L1) → occupancy of LOOP1 is now 1 (= maxHus).
        routing.assignRoute(new RouteRequest(wh, "HU_A", List.of("EXIT")));
        routing.decide(new ScanRequest(wh, "L1", "HU_A"));

        // HU_B at ENTRY would enter LOOP1 toward EXIT, but it's full → HOLD.
        routing.assignRoute(new RouteRequest(wh, "HU_B", List.of("EXIT")));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "ENTRY", "HU_B"));
        assertThat(d.action()).isEqualTo("HOLD");
        assertThat(d.detail()).contains("LOOP1");
    }

    @Test
    void divertsToOverflowWhenTheLoopIsFull() {
        UUID wh = UUID.randomUUID();
        loopTopology(wh, "OVERFLOW", "BUFFER");

        routing.assignRoute(new RouteRequest(wh, "HU_A", List.of("EXIT")));
        routing.decide(new ScanRequest(wh, "L1", "HU_A"));

        routing.assignRoute(new RouteRequest(wh, "HU_B", List.of("EXIT")));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "ENTRY", "HU_B"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("BUFFER");
        assertThat(d.exitCode()).isEqualTo("to_buffer");
        assertThat(d.detail()).contains("overflow");
    }
}
