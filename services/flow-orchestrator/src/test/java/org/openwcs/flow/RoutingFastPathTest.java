package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.api.ReportingDtos.ScanQualityRow;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.openwcs.flow.service.AutomationTopologyService;
import org.openwcs.flow.service.ReportingService;
import org.openwcs.flow.service.RoutingEngine;
import org.openwcs.flow.service.RoutingGraphCache;
import org.openwcs.flow.service.RoutingProjectionService;
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
 * Boots flow-orchestrator against PostgreSQL 16 and exercises the routing FAST PATH: the
 * per-warehouse graph snapshot is invalidated by every graph write (editor replace AND topology
 * projection), the snapshot's precomputed next-hop tables answer exactly like the per-scan
 * Dijkstra engine they replace, asynchronous side effects (counters) eventually persist, and the
 * warm decide() path stays well inside the ~10 ms adapter budget.
 */
@SpringBootTest
@Testcontainers
class RoutingFastPathTest {

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

    @Autowired
    RoutingGraphCache graphCache;

    @Autowired
    RoutingProjectionService projection;

    @Autowired
    AutomationTopologyService automation;

    @Autowired
    ReportingService reporting;

    @Autowired
    ConveyorNodeRepository nodeRepo;

    @Autowired
    ConveyorEdgeRepository edgeRepo;

    private static NodeDto node(String code) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, null);
    }

    private static NodeDto divertNode(String code, String defaultExitCode) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, defaultExitCode);
    }

    // ------------------------------------------------------------------- cache invalidation

    @Test
    void editorReplaceInvalidatesTheSnapshotSoTheNextDecideRoutesTheNewGraph() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(node("IN"), divertNode("DIV", "SHIP"), node("PACK"), node("SHIP")),
                List.of(new EdgeDto("IN", "DIV", "straight", 1),
                        new EdgeDto("DIV", "PACK", "divert", 1),
                        new EdgeDto("DIV", "SHIP", "bypass", 1)),
                List.of(), List.of()));

        // Warms the snapshot: the unplanned tote follows the divert default SHIP.
        RoutingDecision before = routing.decide(new ScanRequest(wh, "DIV", "STRAY-1"));
        assertThat(before.toNode()).isEqualTo("SHIP");

        // Editor save flips the default to PACK; the snapshot TTL is 60 s, so ONLY eviction can
        // make the very next scan see the change.
        topology.replace(wh, new Topology(
                List.of(node("IN"), divertNode("DIV", "PACK"), node("PACK"), node("SHIP")),
                List.of(new EdgeDto("IN", "DIV", "straight", 1),
                        new EdgeDto("DIV", "PACK", "divert", 1),
                        new EdgeDto("DIV", "SHIP", "bypass", 1)),
                List.of(), List.of()));

        RoutingDecision after = routing.decide(new ScanRequest(wh, "DIV", "STRAY-1"));
        assertThat(after.toNode()).isEqualTo("PACK");
    }

    @Test
    void topologyProjectionInvalidatesTheSnapshotSoTheNextDecideRoutesTheProjectedGraph() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(List.of(node("OLDNODE")), List.of(), List.of(), List.of()));

        // Warm the snapshot on the pre-projection graph.
        assertThat(routing.decide(new ScanRequest(wh, "OLDNODE", "STRAY-2")).action()).isEqualTo("NO_ROUTE");

        // Project a placement model: a 10 m two-point conveyor becomes CONV1#0 -> CONV1#1.
        PlacedEquipmentDto conveyor = new PlacedEquipmentDto(UUID.randomUUID(), null, null, "CONV1",
                BigDecimal.valueOf(5), null, BigDecimal.ZERO, BigDecimal.ZERO, null,
                BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE,
                List.of(List.of(0d, 0d), List.of(10d, 0d)), false, List.of(List.of(0, 1)),
                "ACTIVE", "conveyor", null);
        automation.save(wh, new AutomationTopologyDto(List.of(), List.of(conveyor), List.of(), List.of()));
        projection.project(wh);

        // The very next decide must answer over the PROJECTED graph: the old node is gone and the
        // new conveyor routes along its only exit.
        assertThat(routing.decide(new ScanRequest(wh, "OLDNODE", "STRAY-2")).action()).isEqualTo("EXCEPTION");
        RoutingDecision onNew = routing.decide(new ScanRequest(wh, "CONV1#0", "STRAY-2"));
        assertThat(onNew.action()).isEqualTo("ROUTE");
        assertThat(onNew.toNode()).isEqualTo("CONV1#1");
    }

    // ------------------------------------------------- next-hop tables vs the per-scan engine

    @Test
    void precomputedNextHopsEqualTheOldDijkstraEngineOnANonTrivialGraph() {
        UUID wh = UUID.randomUUID();
        // 8 nodes, 11 directed edges including a cycle and an almost-unreachable sink. Costs are
        // distinct powers of two, so every reachable pair has a UNIQUE least-cost path and the two
        // implementations cannot legitimately disagree on tie-breaks.
        topology.replace(wh, new Topology(
                List.of(node("A"), node("B"), node("C"), node("D"), node("E"), node("F"),
                        node("G"), node("H")),
                List.of(new EdgeDto("A", "B", "B", 1),
                        new EdgeDto("B", "C", "C", 2),
                        new EdgeDto("C", "D", "D", 4),
                        new EdgeDto("D", "E", "E", 8),
                        new EdgeDto("E", "F", "F", 16),
                        new EdgeDto("A", "C", "C", 32),
                        new EdgeDto("B", "F", "F", 64),
                        new EdgeDto("C", "F", "F", 128),
                        new EdgeDto("F", "A", "A", 256),
                        new EdgeDto("D", "B", "B", 512),
                        new EdgeDto("G", "A", "A", 1024)),
                List.of(), List.of()));

        List<ConveyorNode> allNodes = nodeRepo.findByWarehouseId(wh);
        List<ConveyorEdge> allEdges = edgeRepo.findByWarehouseId(wh);
        RoutingGraphCache.GraphSnapshot snapshot = graphCache.get(wh);

        int agreed = 0;
        for (ConveyorNode from : allNodes) {
            for (ConveyorNode target : allNodes) {
                if (from.getId().equals(target.getId())) {
                    continue;
                }
                Optional<ConveyorEdge> old = RoutingEngine.nextHop(allEdges, from.getId(), target.getId());
                RoutingGraphCache.CachedEdge fast = snapshot.nextHop(from.getId(), target.getId());
                if (old.isEmpty()) {
                    assertThat(fast)
                            .as("reachability %s -> %s", from.getCode(), target.getCode())
                            .isNull();
                } else {
                    assertThat(fast)
                            .as("reachability %s -> %s", from.getCode(), target.getCode())
                            .isNotNull();
                    assertThat(fast.fromNodeId()).isEqualTo(old.get().getFromNodeId());
                    assertThat(fast.toNodeId())
                            .as("next hop %s -> %s", from.getCode(), target.getCode())
                            .isEqualTo(old.get().getToNodeId());
                    assertThat(fast.exitCode()).isEqualTo(old.get().getExitCode());
                    agreed++;
                }
            }
        }
        // Sanity that the comparison actually covered a meaningful set of reachable pairs.
        assertThat(agreed).isGreaterThan(20);
    }

    // ------------------------------------------------------------------- async side effects

    @Test
    void asyncSideEffectsEventuallyPersistTheScanCounters() throws InterruptedException {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(node("S1"), node("S2")),
                List.of(new EdgeDto("S1", "S2", "straight", 1)),
                List.of(), List.of()));

        routing.decide(new ScanRequest(wh, "S1", "EVENTUAL-1")); // stray rides the only exit
        routing.decide(new ScanRequest(wh, "S1", "NOREAD"));     // read error counts too

        // Polling assert: the counters are written by the background worker, not the scan thread.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        List<ScanQualityRow> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = reporting.scanQuality(wh, 7);
            if (rows.size() == 1 && rows.get(0).scans() == 2) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).node()).isEqualTo("S1");
        assertThat(rows.get(0).scans()).isEqualTo(2);
        assertThat(rows.get(0).noReads()).isEqualTo(1);
        assertThat(rows.get(0).unknowns()).isEqualTo(1);
    }

    // ------------------------------------------------------------------- warm-path timing

    @Test
    void warmDecidePathStaysWellInsideTheTenMillisecondBudget() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(node("INDUCT"), divertNode("DIVERT", "SHIP"), node("PACK"), node("SHIP")),
                List.of(new EdgeDto("INDUCT", "DIVERT", "straight", 1),
                        new EdgeDto("DIVERT", "PACK", "divert", 1),
                        new EdgeDto("DIVERT", "SHIP", "bypass", 1)),
                List.of(), List.of()));
        routing.assignRoute(new RouteRequest(wh, "HU-TIMING", List.of("PACK")));

        // Warm-up: snapshot build + next-hop table + JIT happen here, off the measurement.
        for (int i = 0; i < 50; i++) {
            routing.decide(new ScanRequest(wh, "INDUCT", "HU-TIMING"));
        }

        int n = 200;
        long[] nanos = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            RoutingDecision d = routing.decide(new ScanRequest(wh, "INDUCT", "HU-TIMING"));
            nanos[i] = System.nanoTime() - t0;
            assertThat(d.action()).isEqualTo("ROUTE"); // the full planned-scan path every time
        }
        Arrays.sort(nanos);
        double p50 = nanos[n / 2 - 1] / 1e6;
        double p95 = nanos[(int) (n * 0.95) - 1] / 1e6;
        double p99 = nanos[(int) (n * 0.99) - 1] / 1e6;
        double max = nanos[n - 1] / 1e6;
        // Land the measured numbers in the test output (before/after evidence for the fast path).
        System.out.printf("warm decide() over %d planned scans: p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms%n",
                n, p50, p95, p99, max);

        // Loose on purpose (Testcontainers CI is no benchmark rig): the warm MEDIAN must sit
        // well inside the 10 ms budget; tails are reported, not asserted, to keep CI flake-free.
        assertThat(p50).as("warm p50 decide latency (ms)").isLessThan(10.0);
    }

    // -------------------------------------------------- behaviour unchanged under the cache

    @Test
    void plannedWalkStillCompletesAcrossSnapshotReuse() {
        UUID wh = UUID.randomUUID();
        topology.replace(wh, new Topology(
                List.of(node("IN"), node("MID"), node("OUT")),
                List.of(new EdgeDto("IN", "MID", "straight", 1),
                        new EdgeDto("MID", "OUT", "straight", 1)),
                List.of(), List.of()));
        routing.assignRoute(new RouteRequest(wh, "HU-WALK", List.of("MID", "OUT")));

        List<String> actions = new ArrayList<>();
        actions.add(routing.decide(new ScanRequest(wh, "IN", "HU-WALK")).action());
        actions.add(routing.decide(new ScanRequest(wh, "MID", "HU-WALK")).action());
        actions.add(routing.decide(new ScanRequest(wh, "OUT", "HU-WALK")).action());
        assertThat(actions).containsExactly("ROUTE", "ROUTE", "COMPLETE");
        assertThat(routing.getRoute(wh, "HU-WALK").orElseThrow().status()).isEqualTo("COMPLETED");
    }
}
