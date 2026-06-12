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
 * REQUESTED, in-storage rows), and operator completion marks the flow entry DONE — and dispatches
 * NO transport: flow owns the return-to-storage leg, where only slotting decides the destination.
 * (The lifecycle/cap assertions that used to live here moved to flow's test suite.)
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
    void completeMarksFlowEntryDoneAndCreatesNoTransport() {
        GtpStation s = station("GTP-Q2");
        UUID entryId = UUID.randomUUID();
        InductionEntry done = entry(entryId, s.getId(), s.getWarehouseId(), "DONE", 1L);
        when(induction.markDone(entryId)).thenReturn(done);

        var result = queue.completeInduction(entryId);

        assertThat(result.status()).isEqualTo("DONE");
        verify(induction).markDone(entryId);
        // Flow owns the return leg (only slotting slots a tote): gtp dispatches NO transport.
        verify(flow, never()).createTransport(any(), any(), any(), any(), any());
    }
}
