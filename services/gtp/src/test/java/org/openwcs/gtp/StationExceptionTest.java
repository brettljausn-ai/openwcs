package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.client.FlowClient;
import org.openwcs.gtp.client.FlowInductionClient;
import org.openwcs.gtp.client.FlowInductionClient.InductionEntry;
import org.openwcs.gtp.client.TxLogClient;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.openwcs.gtp.repo.MaintenanceOrderRepository;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.StationExceptionService;
import org.openwcs.gtp.service.StationQueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The act-on-completion behaviours, now driven off the flow-owned induction entry (ADR-0007 §6.2):
 * the dirty-tote exception opens a maintenance order and marks the flow entry DONE (flow owns any
 * return transport — gtp dispatches none); the broken-product exception posts a negative DAMAGED
 * stock adjustment. The old gtp store-back was removed: only slotting (asked by flow) slots a tote.
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
    FlowInductionClient induction;

    @MockBean
    TxLogClient txlog;

    @MockBean
    org.openwcs.gtp.client.MasterDataClient masterData;

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

    private InductionEntry entry(UUID id, GtpStation s, UUID locationId) {
        return new InductionEntry(id, s.getWarehouseId(), s.getId(), UUID.randomUUID(), "HU-1",
                UUID.randomUUID(), "SKU-1", new BigDecimal("5"), "STOCK_COUNT", "DONE", 1L,
                Instant.now(), null, Instant.now(), null, null, locationId);
    }

    @Test
    void completeCreatesNoTransportFlowOwnsTheReturnLeg() {
        GtpStation s = station("GTP-X1");
        UUID entryId = UUID.randomUUID();
        InductionEntry done = entry(entryId, s, UUID.randomUUID());
        when(induction.markDone(entryId)).thenReturn(done);

        assertThat(queue.completeInduction(entryId).status()).isEqualTo("DONE");

        // Only slotting (asked by flow's return leg) slots a tote: gtp dispatches no STORE.
        verify(flow, never()).createTransport(any(), any(), any(), any(), any());
    }

    @Test
    void dirtyToteOpensMaintenanceOrderAndDoesNotStoreBack() {
        GtpStation s = station("GTP-X2");
        UUID entryId = UUID.randomUUID();
        InductionEntry e = entry(entryId, s, UUID.randomUUID());
        when(induction.getEntry(entryId)).thenReturn(e);
        when(induction.markDone(entryId)).thenReturn(e);

        MaintenanceOrder order = exceptions.markDirty(s.getId(), entryId);

        assertThat(order.getReason()).isEqualTo("CLEANING");
        assertThat(order.getStatus()).isEqualTo("OPEN");
        assertThat(maintenance.findById(order.getId())).isPresent();
        verify(induction).markDone(entryId); // entry marked DONE in flow
        verify(flow, never()).createTransport(any(), any(), any(), any(), any()); // no store-back
    }

    @Test
    void brokenPostsNegativeDamagedAdjustment() {
        GtpStation s = station("GTP-X3");
        UUID entryId = UUID.randomUUID();
        InductionEntry e = entry(entryId, s, UUID.randomUUID());
        when(induction.getEntry(entryId)).thenReturn(e);

        BigDecimal adjusted = exceptions.markBroken(entryId, new BigDecimal("2"), "op7");
        assertThat(adjusted).isEqualByComparingTo("2");

        ArgumentCaptor<TxLogClient.StockAdjustment> captor =
                ArgumentCaptor.forClass(TxLogClient.StockAdjustment.class);
        verify(txlog).postStockAdjusted(captor.capture());
        assertThat(captor.getValue().qtyDelta()).isEqualByComparingTo("-2");
        assertThat(captor.getValue().reason()).isEqualTo("DAMAGED");
        assertThat(captor.getValue().skuId()).isEqualTo(e.skuId());
        assertThat(captor.getValue().actor()).isEqualTo("op7");
        // The tote stays in the queue (no DONE) so the operator keeps working it.
        verify(induction, never()).markDone(any());
    }
}
