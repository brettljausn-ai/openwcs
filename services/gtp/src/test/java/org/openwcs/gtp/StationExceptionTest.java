package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.client.FlowClient;
import org.openwcs.gtp.client.TxLogClient;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.repo.MaintenanceOrderRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.StationExceptionService;
import org.openwcs.gtp.service.StationQueueService;
import org.openwcs.gtp.service.StationQueueService.EnqueueCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The act-on-departure behaviours: a completed tote with a known source location and no other active
 * work stores back via an ASRS STORE transport; the dirty-tote exception opens a maintenance order and
 * completes the entry without storing back; the broken-product exception posts a negative DAMAGED
 * stock adjustment.
 */
@SpringBootTest
@Testcontainers
class StationExceptionTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    FlowClient flow;

    @MockBean
    TxLogClient txlog;

    @MockBean
    org.openwcs.gtp.client.SlottingClient slotting;

    @Autowired
    GtpStationService stations;

    @Autowired
    StationQueueService queue;

    @Autowired
    StationExceptionService exceptions;

    @Autowired
    MaintenanceOrderRepository maintenance;

    private GtpStation station(String code) {
        GtpStation s = stations.createStation(new CreateStationRequest(
                UUID.randomUUID(), code, null, "ORDER_LOCATION", List.of("PICKING", "STOCK_COUNT"), List.of()));
        return stations.setCapacity(s.getId(), 4, 2);
    }

    private EnqueueCommand cmd(UUID locationId) {
        return new EnqueueCommand(UUID.randomUUID(), "HU-1", UUID.randomUUID(), "SKU-1",
                new BigDecimal("5"), "STOCK_COUNT", "ASRS", null, null, null, locationId);
    }

    @Test
    void completeWithNoOtherEntryStoresBackToTheBestSlot() {
        GtpStation s = station("GTP-X1");
        UUID bestLocation = UUID.randomUUID();
        // Slotting picks the currently-best put-away slot (not the tote's source location).
        org.mockito.Mockito.when(slotting.bestLocation(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(bestLocation));
        StationQueueEntry e = queue.enqueue(s.getId(), cmd(UUID.randomUUID()));

        assertThat(queue.complete(e.getId()).getStatus()).isEqualTo("DONE");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass((Class) Map.class);
        verify(flow).createTransport(eq(s.getWarehouseId()), eq("ASRS"), eq("STORE"),
                payload.capture(), eq(e.getHuId()));
        assertThat(payload.getValue()).containsEntry("destinationLocationId", bestLocation);
    }

    @Test
    void dirtyToteOpensMaintenanceOrderAndDoesNotStoreBack() {
        GtpStation s = station("GTP-X2");
        StationQueueEntry e = queue.enqueue(s.getId(), cmd(UUID.randomUUID()));

        MaintenanceOrder order = exceptions.markDirty(s.getId(), e.getId());

        assertThat(order.getReason()).isEqualTo("CLEANING");
        assertThat(order.getStatus()).isEqualTo("OPEN");
        assertThat(maintenance.findById(order.getId())).isPresent();
        assertThat(queue.queue(s.getId())).noneMatch(q -> q.getId().equals(e.getId())); // entry DONE
        verify(flow, never()).createTransport(any(), any(), any(), any(), any());
    }

    @Test
    void brokenPostsNegativeDamagedAdjustment() {
        GtpStation s = station("GTP-X3");
        StationQueueEntry e = queue.enqueue(s.getId(), cmd(UUID.randomUUID()));

        BigDecimal adjusted = exceptions.markBroken(e.getId(), new BigDecimal("2"));
        assertThat(adjusted).isEqualByComparingTo("2");

        ArgumentCaptor<TxLogClient.StockAdjustment> captor =
                ArgumentCaptor.forClass(TxLogClient.StockAdjustment.class);
        verify(txlog).postStockAdjusted(captor.capture());
        assertThat(captor.getValue().qtyDelta()).isEqualByComparingTo("-2");
        assertThat(captor.getValue().reason()).isEqualTo("DAMAGED");
        assertThat(captor.getValue().skuId()).isEqualTo(e.getSkuId());
    }
}
