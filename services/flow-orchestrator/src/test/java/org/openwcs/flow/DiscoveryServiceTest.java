package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DiscoveryDtos.Discovery;
import org.openwcs.flow.api.DiscoveryDtos.DiscoveredEdge;
import org.openwcs.flow.api.DiscoveryDtos.DiscoveredNode;
import org.openwcs.flow.api.DiscoveryDtos.ObservationRequest;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.service.DiscoveryService;
import org.openwcs.flow.service.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Boots flow-orchestrator against PostgreSQL 16 and verifies topology learning from observations. */
@SpringBootTest
@Testcontainers
class DiscoveryServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DiscoveryService discovery;

    @Autowired
    TopologyService topology;

    private void observe(UUID wh, String node, String barcode) {
        discovery.ingest(new ObservationRequest(wh, node, barcode, "10.0.0.5", null, null));
    }

    @Test
    void infersNodesEdgesAndTargetsFromObservations() {
        UUID wh = UUID.randomUUID();
        // A is already configured; B/C/SHIP are new (to be discovered).
        topology.replace(wh, new Topology(
                List.of(new NodeDto("A", "A", null, 0d, 0d, null, null, null)), List.of(), List.of(), List.of()));

        // HU1: A → B → SHIP ; HU2: A → C → SHIP
        observe(wh, "A", "HU1");
        observe(wh, "B", "HU1");
        observe(wh, "SHIP", "HU1");
        observe(wh, "A", "HU2");
        observe(wh, "C", "HU2");
        observe(wh, "SHIP", "HU2");

        Discovery d = discovery.discover(wh);

        // Nodes: A known; B, C, SHIP discovered as new.
        assertThat(d.nodes()).extracting(DiscoveredNode::code).contains("A", "B", "C", "SHIP");
        DiscoveredNode a = d.nodes().stream().filter(n -> n.code().equals("A")).findFirst().orElseThrow();
        assertThat(a.known()).isTrue();
        assertThat(d.nodes().stream().filter(n -> n.code().equals("SHIP")).findFirst().orElseThrow().known()).isFalse();

        // Edges: A→B, B→SHIP, A→C, C→SHIP — none configured yet.
        assertThat(d.edges()).extracting(DiscoveredEdge::fromCode, DiscoveredEdge::toCode)
                .contains(org.assertj.core.groups.Tuple.tuple("A", "B"),
                        org.assertj.core.groups.Tuple.tuple("B", "SHIP"),
                        org.assertj.core.groups.Tuple.tuple("A", "C"),
                        org.assertj.core.groups.Tuple.tuple("C", "SHIP"));
        assertThat(d.edges()).allMatch(e -> !e.known());

        // SHIP is the terminal for both HUs → top target with count 2.
        assertThat(d.targets().get(0).code()).isEqualTo("SHIP");
        assertThat(d.targets().get(0).terminalCount()).isEqualTo(2);
    }
}
