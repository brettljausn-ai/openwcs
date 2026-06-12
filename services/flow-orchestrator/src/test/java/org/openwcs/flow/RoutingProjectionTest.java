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
                "ACTIVE", "conveyor", null);

        // A sink box conveyor (no path): start/end nodes along its 4 m length.
        PlacedEquipmentDto sink = new PlacedEquipmentDto(sinkId, null, null, "SINK",
                bd(20), bd(0), bd(0), bd(0), bd(0), bd(4), bd(1), bd(1),
                null, false, null, "ACTIVE", "conveyor", null);

        // A function point on the conveyor at offset 5 m (lands on path index 1, the divert) named D1.
        FunctionPointDto fp = new FunctionPointDto(fpId, convId, "DIVERT", "Divert 1",
                bd(5), "LEFT", "D1", null, "ACTIVE");

        // Connection: conveyor exit -> sink entry.
        ConnectionDto conn = new ConnectionDto(UUID.randomUUID(), convId, sinkId, null, null,
                null, null, "to-sink", "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(
                List.of(), List.of(conveyor, sink), List.of(conn), List.of(fp)));

        // (c) category round-trips through save/load.
        AutomationTopologyDto reloaded = automation.load(wh);
        assertThat(reloaded.equipment()).allSatisfy(p -> assertThat(p.category()).isEqualTo("conveyor"));

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

    @Test
    void divertDefaultDirectionProjectsToTheChosenNeighbourNode() {
        // BRANCH → the branch stub's node; STRAIGHT → the main-line node; no default → null.
        assertThat(projectDivertDefault("BRANCH")).isEqualTo("CONV1#3");
        assertThat(projectDivertDefault("STRAIGHT")).isEqualTo("CONV1#2");
        assertThat(projectDivertDefault(null)).isNull();
    }

    /**
     * Projects the same 4-waypoint conveyor as above (straight run 0->1->2, divert branch 1->3)
     * whose divert FP "D1" carries the given default direction, and returns the projected divert
     * node's default exit code.
     */
    private String projectDivertDefault(String defaultExit) {
        UUID wh = UUID.randomUUID();
        UUID convId = UUID.randomUUID();
        PlacedEquipmentDto conveyor = new PlacedEquipmentDto(convId, null, null, "CONV1",
                bd(5), bd(0), bd(0), bd(0), bd(0), bd(10), bd(1), bd(1),
                List.of(List.of(0d, 0d), List.of(5d, 0d), List.of(10d, 0d), List.of(5d, 5d)),
                false,
                List.of(List.of(0, 1), List.of(1, 2), List.of(1, 3)),
                "ACTIVE", "conveyor", null);
        FunctionPointDto fp = new FunctionPointDto(UUID.randomUUID(), convId, "DIVERT_LEFT", "Divert 1",
                bd(5), "LEFT", "D1", defaultExit, "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(
                List.of(), List.of(conveyor), List.of(), List.of(fp)));
        ProjectionResult result = projection.project(wh);
        assertThat(result.warnings()).isEmpty();

        NodeDto divert = topology.get(wh).nodes().stream()
                .filter(n -> n.code().equals("D1"))
                .findFirst().orElseThrow();
        return divert.defaultExitCode();
    }

    @Test
    void midSectionFunctionPointSplitsTheSectionAndInsertsANode() {
        UUID wh = UUID.randomUUID();
        UUID convId = UUID.randomUUID();

        // A straight 10 m conveyor: path 0=(0,0) -> 1=(10,0), one section [0,1].
        PlacedEquipmentDto conveyor = new PlacedEquipmentDto(convId, null, null, "CONV2",
                bd(5), bd(0), bd(0), bd(0), bd(0), bd(10), bd(1), bd(1),
                List.of(List.of(0d, 0d), List.of(10d, 0d)),
                false,
                List.of(List.of(0, 1)),
                "ACTIVE", "conveyor", null);

        // A SCAN function point mid-section at offset 4 m (not on either endpoint), named SCAN1.
        FunctionPointDto scan = new FunctionPointDto(UUID.randomUUID(), convId, "SCAN", "Scan 1",
                bd(4), "TOP", "SCAN1", null, "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(
                List.of(), List.of(conveyor), List.of(), List.of(scan)));

        ProjectionResult result = projection.project(wh);

        // 2 path nodes + 1 inserted FP node = 3 nodes; the single section became 2 edges.
        assertThat(result.nodes()).isEqualTo(3);
        assertThat(result.edges()).isEqualTo(2);

        Topology graph = topology.get(wh);
        assertThat(graph.nodes().stream().anyMatch(n -> n.code().equals("SCAN1"))).isTrue();

        // The section split into  start -> SCAN1 -> end : SCAN1 has one in-edge and one out-edge.
        long intoScan = graph.edges().stream().filter(e -> e.toCode().equals("SCAN1")).count();
        long outOfScan = graph.edges().stream().filter(e -> e.fromCode().equals("SCAN1")).count();
        assertThat(intoScan).isEqualTo(1);
        assertThat(outOfScan).isEqualTo(1);

        // Cost split proportionally: 4 m before SCAN1, 6 m after; total preserved at 10.
        EdgeDto into = graph.edges().stream().filter(e -> e.toCode().equals("SCAN1")).findFirst().orElseThrow();
        EdgeDto out = graph.edges().stream().filter(e -> e.fromCode().equals("SCAN1")).findFirst().orElseThrow();
        assertThat(into.cost()).isEqualTo(4);
        assertThat(out.cost()).isEqualTo(6);
    }

    @Test
    void closedConveyorProducesALoopWithItsNodesCarryingLoopCode() {
        UUID wh = UUID.randomUUID();
        UUID loopId = UUID.randomUUID();

        // A closed square loop: 4 points, sections chaining them back to the start.
        PlacedEquipmentDto loop = new PlacedEquipmentDto(loopId, null, null, "LOOP1",
                bd(0), bd(0), bd(0), bd(0), bd(0), bd(20), bd(1), bd(1),
                List.of(List.of(0d, 0d), List.of(10d, 0d), List.of(10d, 10d), List.of(0d, 10d)),
                true,
                List.of(List.of(0, 1), List.of(1, 2), List.of(2, 3), List.of(3, 0)),
                "ACTIVE", "conveyor", null);

        automation.save(wh, new AutomationTopologyDto(
                List.of(), List.of(loop), List.of(), List.of()));

        projection.project(wh);

        Topology graph = topology.get(wh);
        // Exactly one loop was inferred.
        assertThat(graph.loops()).hasSize(1);
        String loopCode = graph.loops().get(0).code();
        assertThat(loopCode).isNotBlank();
        assertThat(graph.loops().get(0).whenFull()).isEqualTo("HOLD");

        // Every node of the closed conveyor carries the loop code.
        assertThat(graph.nodes()).hasSize(4);
        assertThat(graph.nodes()).allSatisfy(n -> assertThat(n.loopCode()).isEqualTo(loopCode));
    }

    @Test
    void pointLevelExplicitConnectionStitchesExactlyTheChosenNodes() {
        UUID wh = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();

        // Two straight path conveyors with a 2 m gap between A's end (10,0) and B's start (12,0) -
        // beyond the 1.5 m auto-inference range, so only the explicit link can bridge them.
        PlacedEquipmentDto a = pathConveyor(aId, "CONVA", 0, 10);
        PlacedEquipmentDto b = pathConveyor(bId, "CONVB", 12, 22);

        // Explicit node-level connection: A path point 1 (its end) -> B path point 0 (its start).
        ConnectionDto conn = new ConnectionDto(UUID.randomUUID(), aId, bId, null, null,
                1, 0, null, "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(List.of(), List.of(a, b), List.of(conn), List.of()));
        ProjectionResult result = projection.project(wh);

        assertThat(result.warnings()).isEmpty();
        // 2 nodes per conveyor; 1 section edge each + the explicit link = 3 edges.
        assertThat(result.nodes()).isEqualTo(4);
        assertThat(result.edges()).isEqualTo(3);

        Topology graph = topology.get(wh);
        List<EdgeDto> bridge = graph.edges().stream()
                .filter(e -> e.fromCode().equals("CONVA#1") && e.toCode().equals("CONVB#0"))
                .toList();
        assertThat(bridge).hasSize(1);
        assertThat(bridge.get(0).cost()).isEqualTo(1);
    }

    @Test
    void explicitConnectionOverlappingAutoInferenceProjectsNoDuplicateEdge() {
        UUID wh = UUID.randomUUID();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();

        // A's end (10,0) and B's start (11,0) are 1 m apart, WITHIN the 1.5 m auto-inference range,
        // so the touchpoint would be linked even without the explicit connection.
        PlacedEquipmentDto a = pathConveyor(aId, "CONVA", 0, 10);
        PlacedEquipmentDto b = pathConveyor(bId, "CONVB", 11, 21);
        ConnectionDto conn = new ConnectionDto(UUID.randomUUID(), aId, bId, null, null,
                1, 0, null, "ACTIVE");

        automation.save(wh, new AutomationTopologyDto(List.of(), List.of(a, b), List.of(conn), List.of()));
        ProjectionResult result = projection.project(wh);

        // 2 section edges + the explicit A#1->B#0 + the auto-inferred REVERSE B#0->A#1 only, the
        // forward auto edge is deduplicated against the explicit connection.
        assertThat(result.edges()).isEqualTo(4);
        Topology graph = topology.get(wh);
        long forward = graph.edges().stream()
                .filter(e -> e.fromCode().equals("CONVA#1") && e.toCode().equals("CONVB#0")).count();
        long reverse = graph.edges().stream()
                .filter(e -> e.fromCode().equals("CONVB#0") && e.toCode().equals("CONVA#1")).count();
        assertThat(forward).isEqualTo(1);
        assertThat(reverse).isEqualTo(1);
    }

    /** A straight 2-point path conveyor along z=0 from x=x0 to x=x1, one section [0,1]. */
    private static PlacedEquipmentDto pathConveyor(UUID id, String code, double x0, double x1) {
        return new PlacedEquipmentDto(id, null, null, code,
                bd((x0 + x1) / 2), bd(0), bd(0), bd(0), bd(0), bd(Math.abs(x1 - x0)), bd(1), bd(1),
                List.of(List.of(x0, 0d), List.of(x1, 0d)),
                false,
                List.of(List.of(0, 1)),
                "ACTIVE", "conveyor", null);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }
}
