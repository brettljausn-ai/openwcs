package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.ConnectionDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.service.AutomationTopologyService;
import org.openwcs.flow.service.RoutingProjectionService;
import org.openwcs.flow.service.RoutingProjectionService.ProjectionResult;
import org.openwcs.flow.service.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots flow-orchestrator against PostgreSQL 16 and verifies the routing-graph projection: a
 * 3-point conveyor with a divert (sections [[0,1],[1,2],[1,3]]) plus a downstream connection
 * projects to the expected node/edge counts, the divert point gets two out-edges, a function-point
 * nodeCode aliases the layout node, and the connection stitches exit→entry.
 */
@SpringBootTest
@Testcontainers
class RoutingProjectionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AutomationTopologyService automation;

    @Autowired
    RoutingProjectionService projection;

    @Autowired
    TopologyService topology;

    @Test
    void projectsConveyorWithDivertAndConnection() {
        UUID wh = UUID.randomUUID();

        UUID convId = UUID.randomUUID();
        UUID sinkId = UUID.randomUUID();
        UUID fpId = UUID.randomUUID();

        // A 4-waypoint conveyor: straight run 0->1->2 with a divert 1->3.
        // path index 0=(0,0) 1=(5,0) 2=(10,0) 3=(5,5).
        PlacedEquipmentDto conveyor = new PlacedEquipmentDto(convId, null, null, "CONV1",
                bd(5), bd(0), bd(0), bd(0), bd(0), bd(10), bd(1), bd(1),
                List.of(List.of(0d, 0d), List.of(5d, 0d), List.of(10d, 0d), List.of(5d, 5d)),
                false,
                List.of(List.of(0, 1), List.of(1, 2), List.of(1, 3)),
                "ACTIVE");

        // A sink box conveyor (no path): start/end nodes along its 4 m length.
        PlacedEquipmentDto sink = new PlacedEquipmentDto(sinkId, null, null, "SINK",
                bd(20), bd(0), bd(0), bd(0), bd(0), bd(4), bd(1), bd(1),
                null, false, null, "ACTIVE");

        // A function point on the conveyor at offset 5 m (lands on path index 1, the divert) named D1.
        FunctionPointDto fp = new FunctionPointDto(fpId, convId, "DIVERT", "Divert 1",
                bd(5), "LEFT", "D1", "ACTIVE");

        // Connection: conveyor exit -> sink entry.
        ConnectionDto conn = new ConnectionDto(UUID.randomUUID(), convId, sinkId, null, null,
                "to-sink", "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(
                List.of(), List.of(conveyor, sink), List.of(conn), List.of(fp)));

        ProjectionResult result = projection.project(wh);

        // Conveyor: 4 nodes (indices 0,1,2,3). Sink box: 2 nodes. => 6 nodes.
        assertThat(result.nodes()).isEqualTo(6);
        // Conveyor: 3 section edges. Sink box: 1 edge. Connection: 1 edge. => 5 edges.
        assertThat(result.edges()).isEqualTo(5);

        Topology graph = topology.get(wh);
        assertThat(graph.nodes()).hasSize(6);
        assertThat(graph.edges()).hasSize(5);

        // The divert node was aliased to the function-point nodeCode "D1".
        assertThat(graph.nodes().stream().anyMatch(n -> n.code().equals("D1"))).isTrue();

        // The divert node (now "D1") has two out-edges (to index 2 and to index 3).
        long divertOut = graph.edges().stream().filter(e -> e.fromCode().equals("D1")).count();
        assertThat(divertOut).isEqualTo(2);

        // Every edge carries the target node code as its exitCode.
        for (EdgeDto e : graph.edges()) {
            assertThat(e.exitCode()).isEqualTo(e.toCode());
        }
        // The straight 0->1 run is 5 m, so its cost rounds to 5.
        EdgeDto firstRun = graph.edges().stream()
                .filter(e -> e.toCode().equals("D1"))
                .findFirst().orElseThrow();
        assertThat(firstRun.cost()).isEqualTo(5);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
