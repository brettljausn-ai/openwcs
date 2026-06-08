package org.openwcs.counting;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * ASRS count-tote routing that runs at the end of {@code generate(...)}: with the emulator ON, an
 * ASRS-family cell holding a handling unit, and an active STOCK_COUNT station, the tote is enqueued
 * to the station; with the emulator OFF nothing is routed; and a routing failure never breaks
 * count-task creation.
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

    private CreateCountTaskCommand task(UUID wh, UUID loc, UUID sku) {
        return new CreateCountTaskCommand(
                wh, "LOCATION", loc, "BLIND", "AD_HOC", null, null, BigDecimal.ZERO, null,
                List.of(new CountTaskScope(loc, sku, null, "EACH")));
    }

    @Test
    void emulatorOnAsrsCellWithHuEnqueuesToCountingStation() {
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

        counting.generate(task(wh, loc, sku));

        // Transport requested for the ASRS family and the tote enqueued in STOCK_COUNT mode.
        verify(flow).createTransport(eq(wh), eq("ASRS"), any(), any(), eq(huId));
        org.mockito.ArgumentCaptor<GtpClient.EnqueueRequest> captor =
                org.mockito.ArgumentCaptor.forClass(GtpClient.EnqueueRequest.class);
        verify(gtp).enqueue(eq(station), captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().huId()).isEqualTo(huId);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().mode()).isEqualTo("STOCK_COUNT");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().family()).isEqualTo("ASRS");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().distanceM()).isNull();
    }

    @Test
    void emulatorOffDoesNotRoute() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("5"));
        when(masterData.emulatorEnabled()).thenReturn(false);

        counting.generate(task(wh, loc, sku));

        verify(gtp, never()).findActiveCountingStation(any());
        verify(gtp, never()).enqueue(any(), any());
        verify(flow, never()).createTransport(any(), any(), any(), any(), any());
    }

    @Test
    void routingFailureDoesNotBreakCountTaskCreation() {
        UUID wh = UUID.randomUUID();
        UUID loc = UUID.randomUUID();
        UUID sku = UUID.randomUUID();

        when(inventory.expectedOnHand(wh, sku, loc)).thenReturn(new BigDecimal("5"));
        when(masterData.emulatorEnabled()).thenThrow(new RuntimeException("master-data down"));

        // generate() must still succeed and persist a line despite the routing blow-up.
        var created = counting.generate(task(wh, loc, sku));
        org.assertj.core.api.Assertions.assertThat(counting.rawLines(created.getId())).hasSize(1);
    }
}
