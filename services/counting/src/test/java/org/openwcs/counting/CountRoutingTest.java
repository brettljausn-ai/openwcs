package org.openwcs.counting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
 * STOCK_COUNT station, the tote is enqueued once and the task becomes ROUTED; with the emulator OFF
 * nothing is routed and the task is FAILED; and routing is idempotent (a second routeTask does not
 * enqueue again).
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
        verify(gtp, org.mockito.Mockito.never()).enqueue(any(), any());
    }

    @Test
    void emulatorOnAsrsCellWithHuEnqueuesAndMarksRouted() {
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

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        // Transport requested for the ASRS family and the tote enqueued in STOCK_COUNT mode.
        verify(flow).createTransport(eq(wh), eq("ASRS"), any(), any(), eq(huId));
        org.mockito.ArgumentCaptor<GtpClient.EnqueueRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GtpClient.EnqueueRequest.class);
        verify(gtp).enqueue(eq(station), captor.capture());
        assertThat(captor.getValue().huId()).isEqualTo(huId);
        assertThat(captor.getValue().mode()).isEqualTo("STOCK_COUNT");
        assertThat(captor.getValue().family()).isEqualTo("ASRS");
        assertThat(captor.getValue().distanceM()).isNull();

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
        // AutoStore-stored stock -> dispatch to the AUTOSTORE adapter family, not a hardcoded ASRS.
        when(masterData.storageTypeOfLocation(wh, loc)).thenReturn(Optional.of("AUTOSTORE"));
        when(masterData.skuCode(sku)).thenReturn(Optional.of("SKU-1"));
        when(inventory.findHuAt(wh, sku, loc))
                .thenReturn(Optional.of(new InventoryClient.HandlingUnit(huId, "HU-1", new BigDecimal("12"))));

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        verify(flow).createTransport(eq(wh), eq("AUTOSTORE"), any(), any(), eq(huId));
        org.mockito.ArgumentCaptor<GtpClient.EnqueueRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GtpClient.EnqueueRequest.class);
        verify(gtp).enqueue(eq(station), captor.capture());
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

        verify(gtp, org.mockito.Mockito.never()).enqueue(any(), any());
        verify(flow, org.mockito.Mockito.never()).createTransport(any(), any(), any(), any(), any());

        CountTask after = counting.task(created.getId());
        assertThat(after.getRoutingStatus()).isEqualTo("FAILED");
        assertThat(after.getRoutingReason()).containsIgnoringCase("emulator");
    }

    @Test
    void enqueueRejectionLeavesNoOrphanedTransportAndFailsTheTask() {
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
        // Station in-transit cap reached (the concurrent-routing case): enqueue is the capacity gate.
        org.mockito.Mockito.doThrow(new RuntimeException("Station in-transit cap reached (2)."))
                .when(gtp).enqueue(eq(station), any());

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));

        // Enqueue ran first and was rejected, so no transport was created — nothing for a retry to
        // duplicate. The task is FAILED and will be re-attempted by the sweep once a slot frees up.
        verify(flow, org.mockito.Mockito.never()).createTransport(any(), any(), any(), any(), any());
        CountTask after = counting.task(created.getId());
        assertThat(after.getRoutingStatus()).isEqualTo("FAILED");
        assertThat(after.getRoutingReason()).containsIgnoringCase("cap");
    }

    @Test
    void routeTaskIsIdempotentAndEnqueuesOnlyOnce() {
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

        CountTask created = counting.generate(task(wh, loc, sku));
        routing.routeTask(counting.task(created.getId()));
        // A second pass must not re-route: the line is already marked routed.
        routing.routeTask(counting.task(created.getId()));

        verify(gtp, times(1)).enqueue(eq(station), any());
        verify(flow, times(1)).createTransport(eq(wh), eq("ASRS"), any(), any(), eq(huId));
        assertThat(counting.task(created.getId()).getRoutingStatus()).isEqualTo("ROUTED");
    }
}
