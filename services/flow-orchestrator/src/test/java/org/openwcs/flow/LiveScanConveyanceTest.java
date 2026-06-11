package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.HuTraceView;
import org.openwcs.flow.api.InductionEntryView;
import org.openwcs.flow.api.InductionRequest;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.RouteView;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.client.InventoryClient;
import org.openwcs.flow.client.SlottingClient;
import org.openwcs.flow.client.WorkplaceClient;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.openwcs.flow.service.AutomationTopologyService;
import org.openwcs.flow.service.DeviceTaskService;
import org.openwcs.flow.service.HuTraceService;
import org.openwcs.flow.service.InductionQueueService;
import org.openwcs.flow.service.RoutingService;
import org.openwcs.flow.service.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the flow-orchestrator against PostgreSQL 16 with the device adapter + workplace cap lookup
 * mocked. Exercises ADR-0008 §3d-1 (live scan-driven conveyance, flow side): a CONVEY dispatch on a
 * projected routing graph assigns the HU a route plan and carries entryNode/destinationNode in the
 * payload; an un-projected warehouse dispatches exactly as before (atomic fallback); decide()
 * appends a SCANNED trace row for barcodes that belong to a live transport (and none for strays);
 * and the return leg swaps entry and destination.
 */
@SpringBootTest
@Testcontainers
class LiveScanConveyanceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    DeviceClient deviceClient;

    @MockBean
    WorkplaceClient workplaceClient;

    @MockBean
    InventoryClient inventoryClient;

    // ADR-0009: mocked (unstubbed -> null plan = clear channel) so the live-scan conveyance tests
    // dispatch the RETRIEVE directly, exactly as before the dig-out chain existed.
    @MockBean
    SlottingClient slottingClient;

    @Autowired
    InductionQueueService induction;

    @Autowired
    DeviceTaskService deviceTasks;

    @Autowired
    RoutingService routing;

    @Autowired
    TopologyService topology;

    @Autowired
    AutomationTopologyService automation;

    @Autowired
    HuTraceService traces;

    @Autowired
    HuTransportTraceRepository traceRows;

    private void asyncAdapter() {
        when(deviceClient.execute(any())).thenReturn(
                new DeviceClient.DeviceResult("ACCEPTED", "dispatched", null));
    }

    private void cap(int picking, int other) {
        when(workplaceClient.caps(any())).thenReturn(new WorkplaceClient.Caps(picking, other));
    }

    private InductionRequest req(UUID warehouse, UUID workplace, UUID huId, String huCode) {
        return new InductionRequest(warehouse, workplace, "GTP_STATION", huId, huCode,
                UUID.randomUUID(), "SKU-1", new BigDecimal("12"), UUID.randomUUID(), "STOCK_COUNT",
                "ASRS", null, null);
    }

    private static NodeDto node(String code, double x, double y) {
        return new NodeDto(code, code, null, x, y, null, null, null);
    }

    private static PlacedEquipmentDto placed(UUID id, String code, String category, UUID stationId,
                                             double xM, double zM) {
        return new PlacedEquipmentDto(id, null, null, code,
                BigDecimal.valueOf(xM), null, BigDecimal.valueOf(zM), null, null,
                null, null, null, null, false, null, "ACTIVE", category, stationId);
    }

    private static FunctionPointDto fp(UUID placedId, String functionType, String nodeCode) {
        return new FunctionPointDto(UUID.randomUUID(), placedId, functionType, nodeCode,
                BigDecimal.ZERO, null, nodeCode, "ACTIVE");
    }

    /**
     * A projected graph ASRS_OUT → MID → STATION_IN (plus the way back) with an asrs placement
     * carrying a DISCHARGE function point on ASRS_OUT and a workstation placement (bound to the
     * workplace) carrying an INDUCT function point on STATION_IN.
     */
    private void projectedWarehouse(UUID warehouse, UUID workplace, boolean withFunctionPoints) {
        topology.replace(warehouse, new Topology(
                List.of(node("ASRS_OUT", 0, 0), node("MID", 5, 0), node("STATION_IN", 10, 0)),
                List.of(
                        new EdgeDto("ASRS_OUT", "MID", "straight", 5),
                        new EdgeDto("MID", "STATION_IN", "divert", 5),
                        new EdgeDto("STATION_IN", "MID", "back", 5),
                        new EdgeDto("MID", "ASRS_OUT", "home", 5)),
                List.of(), List.of()));
        UUID asrsId = UUID.randomUUID();
        UUID stationPlacementId = UUID.randomUUID();
        automation.save(warehouse, new AutomationTopologyDto(
                List.of(),
                List.of(placed(asrsId, "ASRS-1", "asrs", null, 0, 0),
                        placed(stationPlacementId, "GTP-1", "workstation", workplace, 10, 0)),
                List.of(),
                withFunctionPoints
                        ? List.of(fp(asrsId, "DISCHARGE", "ASRS_OUT"),
                                fp(stationPlacementId, "INDUCT", "STATION_IN"))
                        : List.of()));
    }

    @Test
    void dispatchOnProjectedGraphAssignsRoutePlanAndPayloadNodes() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        projectedWarehouse(warehouse, workplace, true);

        InductionEntryView created = induction.request(req(warehouse, workplace, UUID.randomUUID(), "TOTE-1"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());

        DeviceTaskView convey = deviceTasks.get(induction.get(created.id()).conveyTaskId());
        assertThat(convey.payload())
                .containsEntry("entryNode", "ASRS_OUT")
                .containsEntry("destinationNode", "STATION_IN");

        RouteView route = routing.getRoute(warehouse, "TOTE-1").orElseThrow();
        assertThat(route.targets()).containsExactly("STATION_IN");
        assertThat(route.status()).isEqualTo("ACTIVE");
    }

    @Test
    void dispatchWithoutGraphKeepsAtomicPayloadAndStillWorks() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID(); // no topology, no placements: un-projected warehouse
        UUID workplace = UUID.randomUUID();

        InductionEntryView created = induction.request(req(warehouse, workplace, UUID.randomUUID(), "TOTE-2"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());

        InductionEntryView inTransit = induction.get(created.id());
        assertThat(inTransit.status()).isEqualTo("IN_TRANSIT");
        DeviceTaskView convey = deviceTasks.get(inTransit.conveyTaskId());
        assertThat(convey.payload()).doesNotContainKeys("entryNode", "destinationNode");
        assertThat(routing.getRoute(warehouse, "TOTE-2")).isEmpty();

        // The atomic leg still completes the pipeline as today.
        deviceTasks.completeFromCallback(inTransit.conveyTaskId(), "COMPLETED", "arrived", Map.of());
        assertThat(induction.get(created.id()).status()).isEqualTo("QUEUED");
    }

    @Test
    void nearestNodeFallbackResolvesWhenNoFunctionPointIsNamed() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        projectedWarehouse(warehouse, workplace, false); // placements only, no nodeCoded FPs

        InductionEntryView created = induction.request(req(warehouse, workplace, UUID.randomUUID(), "TOTE-3"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());

        // ASRS at (0,0) → nearest node ASRS_OUT; workstation at (10,0) → nearest node STATION_IN.
        DeviceTaskView convey = deviceTasks.get(induction.get(created.id()).conveyTaskId());
        assertThat(convey.payload())
                .containsEntry("entryNode", "ASRS_OUT")
                .containsEntry("destinationNode", "STATION_IN");
    }

    @Test
    void decideWritesScannedTraceForLiveTransportAndNoneForStrays() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        projectedWarehouse(warehouse, workplace, true);

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-4"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());

        // The adapter scans the tote at the entry node: ROUTE answered AND traced as SCANNED.
        RoutingDecision d = routing.decide(new ScanRequest(warehouse, "ASRS_OUT", "TOTE-4"));
        assertThat(d.action()).isEqualTo("ROUTE");
        assertThat(d.toNode()).isEqualTo("MID");

        List<HuTraceView> timeline = traces.timeline(huId, warehouse);
        HuTraceView scanned = timeline.stream().filter(t -> "SCANNED".equals(t.event()))
                .findFirst().orElseThrow();
        assertThat(scanned.point()).isEqualTo("ASRS_OUT");
        assertThat(scanned.decision()).isEqualTo("routed to MID via straight");
        assertThat(scanned.toPoint()).isEqualTo("MID");

        // Reaching the destination traces the COMPLETE answer too.
        routing.decide(new ScanRequest(warehouse, "MID", "TOTE-4"));
        routing.decide(new ScanRequest(warehouse, "STATION_IN", "TOTE-4"));
        assertThat(traces.timeline(huId, warehouse).stream()
                .filter(t -> "SCANNED".equals(t.event()))
                .map(HuTraceView::decision)).contains("destination reached");

        // A stray barcode is answered (NO_ROUTE) but never traced.
        long before = traceRows.findByWarehouseId(warehouse).size();
        RoutingDecision stray = routing.decide(new ScanRequest(warehouse, "ASRS_OUT", "GHOST"));
        assertThat(stray.action()).isEqualTo("NO_ROUTE");
        assertThat(traceRows.findByWarehouseId(warehouse)).hasSize((int) before);
    }

    @Test
    void returnLegSwapsEntryAndDestination() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        projectedWarehouse(warehouse, workplace, true);

        InductionEntryView created = induction.request(req(warehouse, workplace, UUID.randomUUID(), "TOTE-5"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        deviceTasks.completeFromCallback(induction.get(created.id()).conveyTaskId(), "COMPLETED", "arrived", Map.of());

        InductionEntryView done = induction.markDone(created.id(), "ASRS", "op");
        DeviceTaskView returnConvey = deviceTasks.get(done.returnConveyTaskId());
        assertThat(returnConvey.payload())
                .containsEntry("entryNode", "STATION_IN")
                .containsEntry("destinationNode", "ASRS_OUT");

        // The route plan now points back at storage.
        RouteView route = routing.getRoute(warehouse, "TOTE-5").orElseThrow();
        assertThat(route.targets()).containsExactly("ASRS_OUT");
        assertThat(route.status()).isEqualTo("ACTIVE");
    }
}
