package org.openwcs.gtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.client.FlowClient;
import org.openwcs.gtp.client.FlowInductionClient;
import org.openwcs.gtp.client.FlowInductionClient.InductionEntry;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.service.GtpStationService;
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
 * Since ADR-0007 Phase 3c-1 the inbound presentation queue ({@code REQUESTED → IN_TRANSIT → QUEUED →
 * DONE}, with the in-transit cap) is owned by flow, not gtp. These tests cover gtp's remaining
 * responsibilities at the workplace: the screen queue feed reads flow's induction slice (incl.
 * REQUESTED, in-storage rows), and operator completion marks the flow entry DONE then runs gtp's
 * store-back. (The lifecycle/cap assertions that used to live here moved to flow's test suite.)
 */
@SpringBootTest
@Testcontainers
class StationQueueTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockBean
    FlowInductionClient induction;

    @MockBean
    FlowClient flow;

    @MockBean
    org.openwcs.gtp.client.SlottingClient slotting;

    @MockBean
    org.openwcs.gtp.client.MasterDataClient masterData;

    @Autowired
    GtpStationService stations;

    @Autowired
    StationQueueService queue;

    private GtpStation station(String code) {
        GtpStation s = stations.createStation(new CreateStationRequest(
                UUID.randomUUID(), code, null, "ORDER_LOCATION", List.of("PICKING", "STOCK_COUNT"), List.of()));
        return stations.setCapacity(s.getId(), 4, 2);
    }

    private InductionEntry entry(UUID id, UUID workplaceId, UUID warehouseId, String status, Long seq) {
        return new InductionEntry(id, warehouseId, workplaceId, UUID.randomUUID(), "HU-1",
                UUID.randomUUID(), "SKU-1", new BigDecimal("5"), "STOCK_COUNT", status, seq,
                Instant.now(), null, status.equals("QUEUED") ? Instant.now() : null, null, null, null);
    }

    @Test
    void queueFeedReadsFlowInductionSliceIncludingRequested() {
        GtpStation s = station("GTP-Q1");
        InductionEntry queued = entry(UUID.randomUUID(), s.getId(), s.getWarehouseId(), "QUEUED", 1L);
        InductionEntry inStorage = entry(UUID.randomUUID(), s.getId(), s.getWarehouseId(), "REQUESTED", null);
        when(induction.readQueue(s.getId())).thenReturn(List.of(queued, inStorage));

        var feed = queue.inductionQueue(s.getId());

        assertThat(feed).extracting(InductionEntry::status)
                .containsExactly("QUEUED", "REQUESTED"); // R3: in-storage rows surfaced too
        verify(induction).readQueue(s.getId());
    }

    @Test
    void completeMarksFlowEntryDoneThenStoresBack() {
        GtpStation s = station("GTP-Q2");
        UUID entryId = UUID.randomUUID();
        InductionEntry done = entry(entryId, s.getId(), s.getWarehouseId(), "DONE", 1L);
        when(induction.markDone(entryId)).thenReturn(done);
        when(induction.readQueue(s.getId())).thenReturn(List.of()); // no other open work for the HU
        UUID bestLocation = UUID.randomUUID();
        when(slotting.bestLocation(any(), any(), any(), any())).thenReturn(java.util.Optional.of(bestLocation));
        when(masterData.storageTypeOfLocation(s.getWarehouseId(), bestLocation))
                .thenReturn(java.util.Optional.of("SHUTTLE_ASRS"));

        var result = queue.completeInduction(entryId);

        assertThat(result.status()).isEqualTo("DONE");
        verify(induction).markDone(entryId);
        verify(flow).createTransport(eq(s.getWarehouseId()), eq("ASRS"), eq("STORE"), any(), eq(done.huId()));
    }

    @Test
    void completeDoesNotStoreBackWhileTheHuStillHasOpenWork() {
        GtpStation s = station("GTP-Q3");
        UUID entryId = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntry done = new InductionEntry(entryId, s.getWarehouseId(), s.getId(), huId, "HU-7",
                UUID.randomUUID(), "SKU-7", new BigDecimal("3"), "PICKING", "DONE", 1L,
                Instant.now(), null, Instant.now(), null, null, null);
        // The same HU still has another QUEUED entry at the workplace -> keep it out of storage.
        InductionEntry other = new InductionEntry(UUID.randomUUID(), s.getWarehouseId(), s.getId(), huId,
                "HU-7", UUID.randomUUID(), "SKU-7", new BigDecimal("3"), "PICKING", "QUEUED", 2L,
                Instant.now(), null, Instant.now(), null, null, null);
        when(induction.markDone(entryId)).thenReturn(done);
        when(induction.readQueue(s.getId())).thenReturn(List.of(other));

        queue.completeInduction(entryId);

        verify(flow, never()).createTransport(any(), any(), any(), any(), any());
    }
}
