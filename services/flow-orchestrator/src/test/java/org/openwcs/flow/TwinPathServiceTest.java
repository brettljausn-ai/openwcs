package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.TwinPathDtos.TotePath;
import org.openwcs.flow.api.TwinPathDtos.TwinPaths;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.domain.HuTransportTrace;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.openwcs.flow.service.TwinPathService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The live-twin "visu master" path read model against PostgreSQL 16: an in-transit tote's resolved
 * polyline is its actual traversed-node sequence (positions from {@code conveyor_node}, in scan
 * order), bounded by the running CONVEY task's start, with the routed-to node as the lead edge. The
 * key property is that the path is the real node sequence, so a divert can never project a tote to a
 * belt's start (the old jump): each waypoint is exactly where the tote was scanned.
 */
@SpringBootTest
@Testcontainers
class TwinPathServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    TwinPathService service;

    @Autowired
    DeviceTaskRepository tasks;

    @Autowired
    ConveyorNodeRepository nodes;

    @Autowired
    HuTransportTraceRepository traces;

    private void node(UUID wh, String code, double x, double y) {
        ConveyorNode n = new ConveyorNode();
        n.setWarehouseId(wh);
        n.setCode(code);
        n.setPosX(x);
        n.setPosY(y);
        nodes.save(n);
    }

    private void scan(UUID wh, UUID huId, String code, String toCode, Instant ts, String decision) {
        HuTransportTrace t = new HuTransportTrace();
        t.setWarehouseId(wh);
        t.setHuId(huId);
        t.setHuCode("HU-1");
        t.setPoint(code);
        t.setEvent("SCANNED");
        t.setToPoint(toCode);
        t.setDecision(decision);
        t.setTs(ts);
        traces.save(t);
    }

    private DeviceTask conveyTask(UUID wh, UUID huId) {
        DeviceTask t = new DeviceTask();
        t.setWarehouseId(wh);
        t.setFamily("CONVEYOR");
        t.setCommand("CONVEY");
        t.setStatus("REQUESTED"); // in-flight
        t.setCorrelationId(huId);
        t.setPayload(Map.of("huId", huId.toString(), "huCode", "HU-1"));
        return tasks.save(t);
    }

    @Test
    void resolvesTheActualTraversedPolylineThroughADivertWithNoJump() {
        UUID wh = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        // A divert: the main run C#3 -> C#fp2 -> C#4 along z=-8, fed from C#9 at z=-9. The two
        // branches meet at C#fp2 — exactly where the frontend's old belt projection went wrong.
        node(wh, "C#9", 1.0, -9.0);
        node(wh, "C#3", 1.0, -8.0);
        node(wh, "C#fp2", 2.06, -8.0);
        node(wh, "C#4", 4.0, -8.0);

        DeviceTask task = conveyTask(wh, hu);
        Instant t0 = task.getCreatedAt();
        // A stale scan from a PRIOR leg (before this CONVEY started) must be excluded.
        scan(wh, hu, "C#4", null, t0.minusSeconds(3600), "destination reached");
        // The current leg, in scan order.
        scan(wh, hu, "C#9", "C#3", t0.plusSeconds(1), "routed to C#3");
        scan(wh, hu, "C#3", "C#fp2", t0.plusSeconds(3), "routed to C#fp2");
        scan(wh, hu, "C#fp2", "C#4", t0.plusSeconds(5), "routed to C#4");
        // A non-node point (e.g. the generic "conveyor") is not a positionable waypoint.
        scan(wh, hu, "conveyor", null, t0.plusSeconds(6), "noise");

        TwinPaths paths = service.paths(wh);

        assertThat(paths.serverNowMs()).isGreaterThan(0);
        assertThat(paths.totes()).hasSize(1);
        TotePath tote = paths.totes().get(0);
        assertThat(tote.huId()).isEqualTo(hu);
        assertThat(tote.huCode()).isEqualTo("HU-1");
        assertThat(tote.state()).isEqualTo("in-transit");
        // Exactly the current leg's positionable nodes, in scan order — the stale C#4 and the
        // non-node "conveyor" row are gone.
        assertThat(tote.waypoints()).extracting("code").containsExactly("C#9", "C#3", "C#fp2");
        assertThat(tote.waypoints()).extracting("x").containsExactly(1.0, 1.0, 2.06);
        assertThat(tote.waypoints()).extracting("z").containsExactly(-9.0, -8.0, -8.0);
        // The lead edge is the last scan's routed-to node, resolved to a position.
        assertThat(tote.next()).isNotNull();
        assertThat(tote.next().code()).isEqualTo("C#4");
        assertThat(tote.next().x()).isEqualTo(4.0);
    }

    @Test
    void recirculatingDecisionFlagsTheState() {
        UUID wh = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        node(wh, "C#1", 0.0, 0.0);
        node(wh, "C#2", 1.0, 0.0);
        DeviceTask task = conveyTask(wh, hu);
        scan(wh, hu, "C#1", "C#2", task.getCreatedAt().plusSeconds(1), "RECIRCULATED at sorter");

        TwinPaths paths = service.paths(wh);

        assertThat(paths.totes()).hasSize(1);
        assertThat(paths.totes().get(0).state()).isEqualTo("recirculating");
    }

    @Test
    void totesWithNoPositionableScanAreOmitted() {
        UUID wh = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        conveyTask(wh, hu); // active CONVEY, but no SCANNED node rows yet
        List<TotePath> totes = service.paths(wh).totes();
        assertThat(totes).isEmpty();
    }
}
