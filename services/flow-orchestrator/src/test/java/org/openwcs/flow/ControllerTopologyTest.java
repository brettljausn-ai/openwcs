package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DiscoveryDtos.Discovery;
import org.openwcs.flow.api.DiscoveryDtos.DiscoveredController;
import org.openwcs.flow.api.DiscoveryDtos.ObservationRequest;
import org.openwcs.flow.api.RoutingDtos.ControllerDto;
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

/**
 * Boots flow-orchestrator against PostgreSQL 16 and verifies conveyor controllers (PLCs): they
 * round-trip through the topology load/save alongside nodes, a node references its controller by
 * code + node-local address, and discovery groups observed nodes by source ip:port into a
 * proposed controller per endpoint.
 */
@SpringBootTest
@Testcontainers
class ControllerTopologyTest {

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
    DiscoveryService discovery;

    @Test
    void controllersRoundTripWithNodesReferencingThem() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(
                        new NodeDto("INDUCT", "Induct", null, 0d, 0d, null, "PLC1", "addr-1", null),
                        new NodeDto("DIVERT", "Divert", null, 10d, 0d, null, "PLC1", "addr-2", null),
                        // A node keeping its legacy per-node hardware address (backward compat).
                        new NodeDto("SHIP", "Ship", "opc.tcp://legacy:4840", 20d, 0d, null, null, null, null)),
                List.of(),
                List.of(),
                List.of(new ControllerDto("PLC1", "Main PLC", "10.0.0.5", 9200))));

        Topology loaded = topology.get(wh);
        assertThat(loaded.controllers()).hasSize(1);
        ControllerDto c = loaded.controllers().get(0);
        assertThat(c.code()).isEqualTo("PLC1");
        assertThat(c.ipAddress()).isEqualTo("10.0.0.5");
        assertThat(c.port()).isEqualTo(9200);

        NodeDto induct = loaded.nodes().stream().filter(n -> n.code().equals("INDUCT")).findFirst().orElseThrow();
        assertThat(induct.controllerCode()).isEqualTo("PLC1");
        assertThat(induct.nodeAddress()).isEqualTo("addr-1");

        NodeDto ship = loaded.nodes().stream().filter(n -> n.code().equals("SHIP")).findFirst().orElseThrow();
        assertThat(ship.hardwareAddress()).isEqualTo("opc.tcp://legacy:4840");
        assertThat(ship.controllerCode()).isNull();
    }

    @Test
    void discoveryProposesAControllerPerSourceEndpoint() {
        UUID wh = UUID.randomUUID();
        // Two PLCs: 10.0.0.5:9200 hosts N1/N2; 10.0.0.6:9200 hosts N3.
        discovery.ingest(new ObservationRequest(wh, "N1", "HU1", "10.0.0.5", 9200, null));
        discovery.ingest(new ObservationRequest(wh, "N2", "HU1", "10.0.0.5", 9200, null));
        discovery.ingest(new ObservationRequest(wh, "N3", "HU1", "10.0.0.6", 9200, null));

        Discovery d = discovery.discover(wh);
        assertThat(d.controllers()).hasSize(2);
        DiscoveredController first = d.controllers().get(0);
        assertThat(first.ipAddress()).isEqualTo("10.0.0.5");
        assertThat(first.port()).isEqualTo(9200);
        assertThat(first.nodeCodes()).containsExactlyInAnyOrder("N1", "N2");
        assertThat(first.known()).isFalse();

        DiscoveredController second = d.controllers().stream()
                .filter(x -> x.ipAddress().equals("10.0.0.6")).findFirst().orElseThrow();
        assertThat(second.nodeCodes()).containsExactly("N3");
    }
}
