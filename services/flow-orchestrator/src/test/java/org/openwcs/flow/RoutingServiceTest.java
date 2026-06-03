package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
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
 * Boots flow-orchestrator against PostgreSQL 16. Builds a small conveyor topology, assigns a
 * handling unit a two-target route, and walks it scan-by-scan — asserting the WCS routes toward
 * the current target via the graph, advances as targets are reached, and completes at the end.
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

    @Test
    void routesAHandlingUnitThroughItsTargetsInSequence() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(
                        new NodeDto("INDUCT", "Induction", "10.0.0.1", 0d, 0d),
                        new NodeDto("DIVERT", "Diverter", "10.0.0.2", 100d, 0d),
                        new NodeDto("PACK", "Pack station", "10.0.0.3", 200d, 50d),
                        new NodeDto("SHIP", "Shipping", "10.0.0.4", 300d, 0d)),
                List.of(
                        new EdgeDto("INDUCT", "DIVERT", "straight", 1),
                        new EdgeDto("DIVERT", "PACK", "divert", 1),
                        new EdgeDto("DIVERT", "SHIP", "bypass", 1),
                        new EdgeDto("PACK", "SHIP", "straight", 1))));

        routing.assignRoute(new RouteRequest(wh, "HU1", List.of("PACK", "SHIP")));

        // At INDUCT, heading to the first target PACK → next hop is toward DIVERT.
        RoutingDecision d1 = routing.decide(new ScanRequest(wh, "INDUCT", "HU1"));
        assertThat(d1.action()).isEqualTo("ROUTE");
        assertThat(d1.currentTarget()).isEqualTo("PACK");
        assertThat(d1.exitCode()).isEqualTo("straight");
        assertThat(d1.toNode()).isEqualTo("DIVERT");

        // At DIVERT, divert toward PACK.
        RoutingDecision d2 = routing.decide(new ScanRequest(wh, "DIVERT", "HU1"));
        assertThat(d2.exitCode()).isEqualTo("divert");
        assertThat(d2.toNode()).isEqualTo("PACK");

        // Arriving at PACK reaches target 1 and re-routes toward the next target SHIP.
        RoutingDecision d3 = routing.decide(new ScanRequest(wh, "PACK", "HU1"));
        assertThat(d3.action()).isEqualTo("ROUTE");
        assertThat(d3.targetReached()).isEqualTo("PACK");
        assertThat(d3.currentTarget()).isEqualTo("SHIP");
        assertThat(d3.toNode()).isEqualTo("SHIP");

        // Arriving at SHIP reaches the final target → route complete.
        RoutingDecision d4 = routing.decide(new ScanRequest(wh, "SHIP", "HU1"));
        assertThat(d4.action()).isEqualTo("COMPLETE");
        assertThat(d4.targetReached()).isEqualTo("SHIP");

        assertThat(routing.getRoute(wh, "HU1").orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void unknownBarcodeHasNoRoute() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(List.of(new NodeDto("N1", null, null, 0d, 0d)), List.of()));
        RoutingDecision d = routing.decide(new ScanRequest(wh, "N1", "GHOST"));
        assertThat(d.action()).isEqualTo("NO_ROUTE");
    }
}
