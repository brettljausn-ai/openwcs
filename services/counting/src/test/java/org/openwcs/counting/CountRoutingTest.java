package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.client.TxLogClient;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.service.CountRoutingService;
import org.openwcs.counting.service.CountTaskScope;
import org.openwcs.counting.service.CountingService;
import org.openwcs.counting.service.CreateCountTaskCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the counting service against PostgreSQL with every outbound client mocked, and verifies the
 * ASRS count-tote routing performed by {@link CountRoutingService#routeTask}. A new task is created
 * PENDING (generate no longer routes synchronously); routeTask then computes and persists the
 * outcome. With the emulator ON, an ASRS-family cell holding a handling unit, and an active
 * STOCK_COUNT station, the tote is presented via a single flow induction request and the task becomes
 * ROUTED; with the emulator OFF nothing is routed and the task is FAILED; and routing is idempotent
 * (a second routeTask does not request presentation again).
 *
 * <p>ADR-0007 Phase 3c-1: the inbound induction queue relocated to flow-orchestrator, which now owns
 * retrieve + convey. Counting issues exactly one {@code flow.requestPresentation} per routed tote and
 * never calls {@code gtp.enqueue} / {@code flow.createTransport}.
 */
@SpringBootTest
@Testcontainers
class CountRoutingTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    InventoryClient inventory;

    @MockBean
    TxLogClient txlog;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    GtpClient gtp;

    @MockBean
    FlowClient flow;

    @Autowired
    CountingService counting;

    @Autowired
    CountRoutingService routing;

    private CreateCountTaskCommand task(UUID wh, UUID loc, UUID sku) {
        return new CreateCountTaskCommand(
                wh, "LOCATION", loc, "BLIND", "AD_HOC", null, null, BigDecimal.ZERO, null,
                List.of(new CountTaskScope(loc, sku, null, "EACH")));
    }

    @Test
    void newTaskStartsPendingAndGenerateDoesNotRoute() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("5"));

        CountTask created = counting.generate(task(wh, loc, sku));

        // generate() no longer routes — the task is left PENDING for the background sweep / routeTask.
        assertThat(created.getRoutingStatus()).isEqualTo("PENDING");
        verify(flow, org.mockito.Mockito.never()).requestPresentation(any());
    }

    @Test
    void emulatorOnAsrsCellWithHuRequestsPresentationAndMarksRouted() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("12"));
        when(masterData.emulatorEnabled()).thenReturn(true);
        when(gtp.findActiveCountingStation(wh)).thenReturn(Optional.of(station));
        when(masterData.storageTypeOfLocation(wh, loc)).thenReturn(Optional.of("SHUTTLE_ASRS"));
        when(masterData.skuCode(sku)).thenReturn(Optional.of("SKU-1"));
        when(inventory.findHuAt(wh, sku, loc))
                .thenReturn(Optional.of(new InventoryClient.HandlingUnit(huId, "HU-1", new BigDecimal("12"))));
        when(flow.requestPresentation(any())).thenReturn(UUID.randomUUID());

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        // Exactly one flow induction request carries the tote, station as destination workplace, the
        // STOCK_COUNT mode, and the resolved ASRS device family. No gtp.enqueue / createTransport.
        org.mockito.ArgumentCaptor<FlowClient.InductionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(FlowClient.InductionRequest.class);
        verify(flow).requestPresentation(captor.capture());
        FlowClient.InductionRequest req = captor.getValue();
        assertThat(req.warehouseId()).isEqualTo(wh);
        assertThat(req.workplaceId()).isEqualTo(station);
        assertThat(req.workplaceKind()).isEqualTo("GTP_STATION");
        assertThat(req.huId()).isEqualTo(huId);
        assertThat(req.mode()).isEqualTo("STOCK_COUNT");
        assertThat(req.family()).isEqualTo("ASRS");
        assertThat(req.locationId()).isEqualTo(loc);

        verify(gtp, org.mockito.Mockito.never()).enqueue(any(), any());
        verify(flow, org.mockito.Mockito.never())
                .createTransport(any(), any(), any(), any(), any());

        assertThat(counting.task(created.getId()).getRoutingStatus()).isEqualTo("ROUTED");
    }

    @Test
    void autostoreCellRoutesToTheAutostoreFamily() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("12"));
        when(masterData.emulatorEnabled()).thenReturn(true);
        when(gtp.findActiveCountingStation(wh)).thenReturn(Optional.of(station));
        // AutoStore-stored stock -> resolve to the AUTOSTORE adapter family, not a hardcoded ASRS.
        when(masterData.storageTypeOfLocation(wh, loc)).thenReturn(Optional.of("AUTOSTORE"));
        when(masterData.skuCode(sku)).thenReturn(Optional.of("SKU-1"));
        when(inventory.findHuAt(wh, sku, loc))
                .thenReturn(Optional.of(new InventoryClient.HandlingUnit(huId, "HU-1", new BigDecimal("12"))));
        when(flow.requestPresentation(any())).thenReturn(UUID.randomUUID());

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        org.mockito.ArgumentCaptor<FlowClient.InductionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(FlowClient.InductionRequest.class);
        verify(flow).requestPresentation(captor.capture());
        assertThat(captor.getValue().family()).isEqualTo("AUTOSTORE");
        assertThat(counting.task(created.getId()).getRoutingStatus()).isEqualTo("ROUTED");
    }

    @Test
    void emulatorOffDoesNotRouteAndMarksFailed() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("5"));
        when(masterData.emulatorEnabled()).thenReturn(false);

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        verify(flow, org.mockito.Mockito.never()).requestPresentation(any());

        CountTask after = counting.task(created.getId());
        assertThat(after.getRoutingStatus()).isEqualTo("FAILED");
        assertThat(after.getRoutingReason()).containsIgnoringCase("emulator");
    }

    @Test
    void aFlowRequestFailureLeavesTheTaskFailedForRetry() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("12"));
        when(masterData.emulatorEnabled()).thenReturn(true);
        when(gtp.findActiveCountingStation(wh)).thenReturn(Optional.of(station));
        when(masterData.storageTypeOfLocation(wh, loc)).thenReturn(Optional.of("SHUTTLE_ASRS"));
        when(masterData.skuCode(sku)).thenReturn(Optional.of("SKU-1"));
        when(inventory.findHuAt(wh, sku, loc))
                .thenReturn(Optional.of(new InventoryClient.HandlingUnit(huId, "HU-1", new BigDecimal("12"))));
        // The induction request to flow blew up (network/5xx). The capacity gate no longer lives here
        // — REQUESTED is uncapped in flow — so this is a generic transport failure, not a cap reject.
        org.mockito.Mockito.doThrow(new RuntimeException("flow induction request failed"))
                .when(flow).requestPresentation(any());

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        // The line is left unrouted; the task is FAILED and will be re-attempted by the sweep.
        CountTask after = counting.task(created.getId());
        assertThat(after.getRoutingStatus()).isEqualTo("FAILED");
        assertThat(after.getRoutingReason()).containsIgnoringCase("transport request failed");
    }

    @Test
    void routeTaskIsIdempotentAndRequestsPresentationOnlyOnce() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("12"));
        when(masterData.emulatorEnabled()).thenReturn(true);
        when(gtp.findActiveCountingStation(wh)).thenReturn(Optional.of(station));
        when(masterData.storageTypeOfLocation(wh, loc)).thenReturn(Optional.of("SHUTTLE_ASRS"));
        when(masterData.skuCode(sku)).thenReturn(Optional.of("SKU-1"));
        when(inventory.findHuAt(wh, sku, loc))
                .thenReturn(Optional.of(new InventoryClient.HandlingUnit(huId, "HU-1", new BigDecimal("12"))));
        when(flow.requestPresentation(any())).thenReturn(UUID.randomUUID());

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));
        // A second pass must not re-route: the line is already marked routed.
        routing.routeTask(counting.task(created.getId()));

        verify(flow, times(1)).requestPresentation(any());
        assertThat(counting.task(created.getId()).getRoutingStatus()).isEqualTo("ROUTED");
    }
}
