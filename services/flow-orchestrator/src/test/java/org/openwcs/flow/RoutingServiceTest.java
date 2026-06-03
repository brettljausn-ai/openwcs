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
        return new NodeDto(code, code, null, 0d, 0d, null);
    }

    private static NodeDto loopNode(String code, String loop) {
        return new NodeDto(code, code, null, 0d, 0d, loop);
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
                List.of()));

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
        topology.replace(wh, new Topology(List.of(node("N1")), List.of(), List.of()));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "N1", "GHOST"));
        assertThat(d.action()).isEqualTo("NO_ROUTE");
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
                List.of(new LoopDto("LOOP1", 1, whenFull, overflowTarget))));
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
