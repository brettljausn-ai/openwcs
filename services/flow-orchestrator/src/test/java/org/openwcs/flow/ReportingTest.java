package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.ReportingDtos.DecisionLatencyStats;
import org.openwcs.flow.api.ReportingDtos.DeviceMovementRow;
import org.openwcs.flow.api.ReportingDtos.ScanQualityRow;
import org.openwcs.flow.api.ReportingDtos.StorageMovementRow;
import org.openwcs.flow.api.ReportingDtos.TrafficRow;
import org.openwcs.flow.api.ReportingDtos.TransitTimeRow;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.domain.HuTransportTrace;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.openwcs.flow.service.DecisionLatencyTracker;
import org.openwcs.flow.service.ReportingService;
import org.openwcs.flow.service.RoutingService;
import org.openwcs.flow.service.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots flow-orchestrator against PostgreSQL 16 and exercises the Reporting aggregates: the
 * scan-quality and edge-traffic daily counters the routing scan path bumps (atomic upserts, one
 * row per node/edge and day), and the per-day reports computed over device tasks and the HU
 * transport trace (storage movements, device throughput, induct-to-arrival transit times).
 */
@SpringBootTest
@Testcontainers
class ReportingTest {

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
    ReportingService reporting;

    @Autowired
    DecisionLatencyTracker decisionLatency;

    @Autowired
    HuTransportTraceRepository traces;

    @Autowired
    JdbcTemplate jdbc;

    private static NodeDto node(String code) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, null);
    }

    private static NodeDto divertNode(String code, String defaultExitCode) {
        return new NodeDto(code, code, null, 0d, 0d, null, null, null, defaultExitCode);
    }

    // INDUCT → DIVERT → {PACK, SHIP}: DIVERT defaults to SHIP for unplanned/no-read scans.
    private void divertTopology(UUID wh) {
        topology.replace(wh, new Topology(
                List.of(node("INDUCT"), divertNode("DIVERT", "SHIP"), node("PACK"), node("SHIP")),
                List.of(
                        new EdgeDto("INDUCT", "DIVERT", "straight", 1),
                        new EdgeDto("DIVERT", "PACK", "divert", 1),
                        new EdgeDto("DIVERT", "SHIP", "bypass", 1)),
                List.of(), List.of()));
    }

    // ------------------------------------------------------------------ scan-quality counters

    @Test
    void scansNoReadsAndUnknownsAccumulateIntoOneRowPerNodeAndDay() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh);
        routing.assignRoute(new RouteRequest(wh, "HU1", List.of("PACK")));

        routing.decide(new ScanRequest(wh, "INDUCT", "HU1"));   // planned scan
        routing.decide(new ScanRequest(wh, "DIVERT", "HU1"));   // planned scan
        routing.decide(new ScanRequest(wh, "DIVERT", "NOREAD")); // read error
        routing.decide(new ScanRequest(wh, "DIVERT", ""));       // read error (blank)
        routing.decide(new ScanRequest(wh, "DIVERT", "GHOST"));  // unknown barcode (no plan)

        List<ScanQualityRow> rows = reporting.scanQuality(wh, 7);
        assertThat(rows).hasSize(2); // one row per node and day: same-day upserts accumulate

        ScanQualityRow induct = rows.stream().filter(r -> r.node().equals("INDUCT")).findFirst().orElseThrow();
        assertThat(induct.scans()).isEqualTo(1);
        assertThat(induct.noReads()).isZero();
        assertThat(induct.unknowns()).isZero();

        ScanQualityRow divert = rows.stream().filter(r -> r.node().equals("DIVERT")).findFirst().orElseThrow();
        assertThat(divert.scans()).isEqualTo(4);
        assertThat(divert.noReads()).isEqualTo(2);
        assertThat(divert.unknowns()).isEqualTo(1);
    }

    @Test
    void scanQualityFiltersOutDaysBeforeTheWindow() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh);
        routing.decide(new ScanRequest(wh, "INDUCT", "STRAY")); // today's row (INDUCT has one exit)

        jdbc.update("INSERT INTO flow.scan_stat (warehouse_id, node_code, day, scans, no_reads, unknowns)"
                + " VALUES (?, 'OLD-NODE', CURRENT_DATE - 30, 9, 1, 1)", wh);

        assertThat(reporting.scanQuality(wh, 7)).hasSize(1)
                .allSatisfy(r -> assertThat(r.node()).isEqualTo("INDUCT"));
        assertThat(reporting.scanQuality(wh, 90)).hasSize(2)
                .anySatisfy(r -> assertThat(r.node()).isEqualTo("OLD-NODE"));
    }

    // --------------------------------------------------------------------- edge-traffic counters

    @Test
    void routeAnswersIncludingDivertDefaultsCountEdgeTraffic() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh);
        routing.assignRoute(new RouteRequest(wh, "HU1", List.of("PACK")));

        routing.decide(new ScanRequest(wh, "INDUCT", "HU1"));    // ROUTE INDUCT -> DIVERT
        routing.decide(new ScanRequest(wh, "DIVERT", "HU1"));    // ROUTE DIVERT -> PACK (planned)
        routing.decide(new ScanRequest(wh, "PACK", "HU1"));      // COMPLETE: no hop counted
        routing.decide(new ScanRequest(wh, "DIVERT", "GHOST"));  // ROUTE DIVERT -> SHIP (default)
        routing.decide(new ScanRequest(wh, "DIVERT", "NOREAD")); // ROUTE DIVERT -> SHIP (default)

        List<TrafficRow> rows = reporting.traffic(wh, 7);
        assertThat(rows).hasSize(3); // same-day upserts accumulate: one row per edge and day
        assertThat(edgeCount(rows, "INDUCT", "DIVERT")).isEqualTo(1);
        assertThat(edgeCount(rows, "DIVERT", "PACK")).isEqualTo(1);
        assertThat(edgeCount(rows, "DIVERT", "SHIP")).isEqualTo(2);
    }

    private static long edgeCount(List<TrafficRow> rows, String from, String to) {
        return rows.stream()
                .filter(r -> r.fromNode().equals(from) && r.toNode().equals(to))
                .mapToLong(TrafficRow::count).sum();
    }

    // ------------------------------------------------------------------ decision latency metric

    @Test
    void decisionLatencyReportsRingBufferPercentilesAfterDecides() {
        UUID wh = UUID.randomUUID();
        divertTopology(wh);
        routing.assignRoute(new RouteRequest(wh, "HU-LAT", List.of("PACK")));

        // 200 warm decides at INDUCT (the plan keeps steering toward PACK, never reached here),
        // so every call exercises the full planned-scan path.
        for (int i = 0; i < 200; i++) {
            routing.decide(new ScanRequest(wh, "INDUCT", "HU-LAT"));
        }

        DecisionLatencyStats stats = decisionLatency.stats();
        // Land the measured numbers in the test output (before/after evidence for the fast path).
        System.out.printf("decision latency over %d decides: p50=%.3f ms p95=%.3f ms p99=%.3f ms max=%.3f ms%n",
                stats.count(), stats.p50Ms(), stats.p95Ms(), stats.p99Ms(), stats.maxMs());

        assertThat(stats.count()).isGreaterThanOrEqualTo(200);
        assertThat(stats.p50Ms()).isGreaterThan(0);
        assertThat(stats.p50Ms()).isLessThanOrEqualTo(stats.p95Ms());
        assertThat(stats.p95Ms()).isLessThanOrEqualTo(stats.p99Ms());
        assertThat(stats.p99Ms()).isLessThanOrEqualTo(stats.maxMs());
    }

    // ----------------------------------------------------------- storage movements (device_task)

    @Test
    void storageMovementsAggregateCompletedStoreAndRetrieveTasksPerLocation() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();

        seedTask(wh, "ASRS", "STORE", loc, "COMPLETED", 0);
        seedTask(wh, "ASRS", "STORE", loc, "COMPLETED", 0);
        seedTask(wh, "ASRS", "RETRIEVE", loc, "COMPLETED", 0);
        seedTask(wh, "ASRS", "STORE", loc, "FAILED", 0);        // not a movement
        seedTask(wh, "CONVEYOR", "CONVEY", null, "COMPLETED", 0); // not a storage command
        seedTask(wh, "ASRS", "RETRIEVE", loc, "COMPLETED", 30);  // outside a 7-day window

        List<StorageMovementRow> rows = reporting.storageMovements(wh, 7);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).locationId()).isEqualTo(loc);
        assertThat(rows.get(0).stores()).isEqualTo(2);
        assertThat(rows.get(0).retrieves()).isEqualTo(1);

        // The wider window picks up the older retrieve as its own day row.
        assertThat(reporting.storageMovements(wh, 90).stream()
                .mapToLong(StorageMovementRow::retrieves).sum()).isEqualTo(2);
    }

    // ------------------------------------------------------------ device movements (device_task)

    @Test
    void deviceMovementsCountCompletedAndFailedPerEquipmentAndDay() {
        UUID wh = UUID.randomUUID();
        UUID shuttle = UUID.randomUUID();
        jdbc.update("INSERT INTO flow.placed_equipment (warehouse_id, equipment_id, code)"
                + " VALUES (?, ?, 'SHUTTLE-1')", wh, shuttle);

        seedEquipmentTask(wh, "ASRS", shuttle, "COMPLETED");
        seedEquipmentTask(wh, "ASRS", shuttle, "COMPLETED");
        seedEquipmentTask(wh, "ASRS", shuttle, "FAILED");
        seedEquipmentTask(wh, "CONVEYOR", null, "COMPLETED"); // no equipment: labelled by family

        List<DeviceMovementRow> rows = reporting.deviceMovements(wh, 7);
        assertThat(rows).hasSize(2);

        DeviceMovementRow asrs = rows.stream().filter(r -> r.family().equals("ASRS")).findFirst().orElseThrow();
        assertThat(asrs.equipment()).isEqualTo("SHUTTLE-1"); // placed-equipment code is the label
        assertThat(asrs.completed()).isEqualTo(2);
        assertThat(asrs.failed()).isEqualTo(1);

        DeviceMovementRow conveyor = rows.stream().filter(r -> r.family().equals("CONVEYOR")).findFirst()
                .orElseThrow();
        assertThat(conveyor.equipment()).isEqualTo("CONVEYOR"); // family fallback
        assertThat(conveyor.completed()).isEqualTo(1);
        assertThat(conveyor.failed()).isZero();
    }

    // --------------------------------------------------------- transit times (hu_transport_trace)

    @Test
    void transitTimesAggregateInductedToArrivedPerDayWithP50AndP95() {
        UUID wh = UUID.randomUUID();
        Instant base = Instant.now().minusSeconds(3600);

        seedTransit(wh, base, 10_000);
        seedTransit(wh, base, 20_000);
        seedTransit(wh, base, 40_000);
        // Still in transit (INDUCTED, no ARRIVED): contributes no sample.
        trace(wh, UUID.randomUUID(), "INDUCTED", base);

        List<TransitTimeRow> rows = reporting.transitTimes(wh, 7);
        assertThat(rows).hasSize(1);
        TransitTimeRow day = rows.get(0);
        assertThat(day.count()).isEqualTo(3);
        assertThat(day.p50Ms()).isEqualTo(20_000); // nearest-rank percentile over {10s, 20s, 40s}
        assertThat(day.p95Ms()).isEqualTo(40_000);
    }

    // ------------------------------------------------------------------------------ test seeding

    /** One INDUCTED + ARRIVED trace pair for a fresh induction entry, {@code transitMs} apart. */
    private void seedTransit(UUID wh, Instant inductedAt, long transitMs) {
        UUID entry = UUID.randomUUID();
        trace(wh, entry, "INDUCTED", inductedAt);
        trace(wh, entry, "ARRIVED", inductedAt.plusMillis(transitMs));
    }

    private void trace(UUID wh, UUID entryId, String event, Instant ts) {
        HuTransportTrace t = new HuTransportTrace();
        t.setWarehouseId(wh);
        t.setHuId(UUID.randomUUID());
        t.setHuCode("HU-" + event);
        t.setEvent(event);
        t.setTs(ts);
        t.setInductionEntryId(entryId);
        traces.save(t);
    }

    private void seedTask(UUID wh, String family, String command, UUID locationId, String status,
                          int daysAgo) {
        String payload = locationId == null ? "{}" : "{\"locationId\":\"" + locationId + "\"}";
        jdbc.update("INSERT INTO flow.device_task (warehouse_id, family, command, payload, status,"
                        + " created_at) VALUES (?, ?, ?, ?::jsonb, ?, now() - make_interval(days => ?))",
                wh, family, command, payload, status, daysAgo);
    }

    private void seedEquipmentTask(UUID wh, String family, UUID equipmentId, String status) {
        jdbc.update("INSERT INTO flow.device_task (warehouse_id, family, equipment_id, command,"
                        + " payload, status) VALUES (?, ?, ?, 'MOVE', '{}'::jsonb, ?)",
                wh, family, equipmentId, status);
    }
}
