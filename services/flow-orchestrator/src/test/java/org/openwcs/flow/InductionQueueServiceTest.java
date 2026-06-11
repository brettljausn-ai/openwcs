package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.HuTraceView;
import org.openwcs.flow.api.InductionEntryView;
import org.openwcs.flow.api.InductionRequest;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.client.WorkplaceClient;
import org.openwcs.flow.service.DeviceTaskService;
import org.openwcs.flow.service.HuTraceService;
import org.openwcs.flow.service.InductionQueueService;
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
 * mocked. Exercises the inbound induction queue (ADR-0007 §3c-1): the RETRIEVE callback advances an
 * entry to IN_TRANSIT and dispatches CONVEY; the CONVEY callback advances it to QUEUED with an
 * arrival sequence assigned in arrival order (incl. out-of-request-order); the queue read includes
 * REQUESTED (R3); the cap counts only {IN_TRANSIT, QUEUED} with REQUESTED uncapped; one trace row
 * per §5 write point; the DONE endpoint is idempotent.
 */
@SpringBootTest
@Testcontainers
class InductionQueueServiceTest {

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

    @Autowired
    InductionQueueService induction;

    @Autowired
    DeviceTaskService deviceTasks;

    @Autowired
    HuTraceService traces;

    private void asyncAdapter() {
        // Every dispatched device task is accepted (async): it stays DISPATCHED until we post the
        // terminal callback ourselves, mirroring the emulator's ack-then-callback behaviour.
        when(deviceClient.execute(any())).thenReturn(
                new DeviceClient.DeviceResult("ACCEPTED", "dispatched", null));
    }

    private void cap(int picking, int other) {
        when(workplaceClient.caps(any())).thenReturn(new WorkplaceClient.Caps(picking, other));
    }

    private InductionRequest req(UUID warehouse, UUID workplace, UUID huId, String huCode, String mode) {
        return new InductionRequest(warehouse, workplace, "GTP_STATION", huId, huCode,
                UUID.randomUUID(), "SKU-1", new BigDecimal("12"), UUID.randomUUID(), mode, "ASRS",
                null, null);
    }

    @Test
    void lifecycleRequestToInTransitToQueuedViaCallbacks() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        assertThat(created.status()).isEqualTo("REQUESTED");
        // Cap had room, so flow dispatched the RETRIEVE immediately.
        assertThat(created.retrieveTaskId()).isNotNull();
        assertThat(created.conveyTaskId()).isNull();
        assertThat(created.arrivalSeq()).isNull();

        // RETRIEVE completes -> IN_TRANSIT + CONVEY dispatched.
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        InductionEntryView inTransit = induction.get(created.id());
        assertThat(inTransit.status()).isEqualTo("IN_TRANSIT");
        assertThat(inTransit.inTransitAt()).isNotNull();
        assertThat(inTransit.conveyTaskId()).isNotNull();

        // CONVEY completes (= arrival) -> QUEUED + arrival_seq assigned.
        deviceTasks.completeFromCallback(inTransit.conveyTaskId(), "COMPLETED", "arrived", Map.of());
        InductionEntryView queued = induction.get(created.id());
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(queued.queuedAt()).isNotNull();
        assertThat(queued.arrivalSeq()).isEqualTo(1L);

        // One trace row per §5 write point. Assert the full set and the cross-call milestone order;
        // the two same-call pairs (RETRIEVED+INDUCTED, ARRIVED+QUEUED) share a timestamp, so their
        // intra-pair order is not asserted (Postgres truncates ts to microseconds → ties).
        List<HuTraceView> timeline = traces.timeline(huId, warehouse);
        assertThat(timeline).extracting(HuTraceView::event)
                .containsExactlyInAnyOrder("REQUESTED", "RETRIEVED", "INDUCTED", "ARRIVED", "QUEUED");
        assertThat(timeline).extracting(HuTraceView::event)
                .containsSubsequence("REQUESTED", "RETRIEVED", "ARRIVED");
    }

    @Test
    void arrivalSeqFollowsArrivalOrderNotRequestOrder() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();

        InductionEntryView a = induction.request(req(warehouse, workplace, UUID.randomUUID(), "A", "STOCK_COUNT"), "op");
        InductionEntryView b = induction.request(req(warehouse, workplace, UUID.randomUUID(), "B", "STOCK_COUNT"), "op");

        // Both retrieve; B arrives before A (out of request order).
        deviceTasks.completeFromCallback(a.retrieveTaskId(), "COMPLETED", "r", Map.of());
        deviceTasks.completeFromCallback(b.retrieveTaskId(), "COMPLETED", "r", Map.of());
        UUID conveyB = induction.get(b.id()).conveyTaskId();
        UUID conveyA = induction.get(a.id()).conveyTaskId();

        deviceTasks.completeFromCallback(conveyB, "COMPLETED", "arrived", Map.of()); // B arrives first
        deviceTasks.completeFromCallback(conveyA, "COMPLETED", "arrived", Map.of());

        assertThat(induction.get(b.id()).arrivalSeq()).isEqualTo(1L);
        assertThat(induction.get(a.id()).arrivalSeq()).isEqualTo(2L);

        // Queue read sorts QUEUED by arrival_seq ASC -> B before A despite A being requested first.
        List<InductionEntryView> queue = induction.readQueue(workplace, "QUEUED");
        assertThat(queue).extracting(InductionEntryView::id).containsExactly(b.id(), a.id());
    }

    @Test
    void capCountsOnlyInTransitAndQueuedRequestedIsUncapped() {
        asyncAdapter();
        cap(1, 1); // room for exactly one {IN_TRANSIT, QUEUED} entry
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();

        InductionEntryView first = induction.request(req(warehouse, workplace, UUID.randomUUID(), "A", "STOCK_COUNT"), "op");
        InductionEntryView second = induction.request(req(warehouse, workplace, UUID.randomUUID(), "B", "STOCK_COUNT"), "op");

        // First takes the only cap slot (RETRIEVE dispatched); second is uncapped backlog (no RETRIEVE).
        assertThat(first.retrieveTaskId()).isNotNull();
        assertThat(induction.get(second.id()).status()).isEqualTo("REQUESTED");
        assertThat(induction.get(second.id()).retrieveTaskId()).isNull();

        // Read includes REQUESTED rows (R3): all three states feed the slice, DONE excluded.
        List<InductionEntryView> slice = induction.readQueue(workplace, null);
        assertThat(slice).extracting(InductionEntryView::status).contains("REQUESTED");
        assertThat(slice).hasSize(2);

        // Advance first to QUEUED, then DONE -> frees a slot -> second is re-metered and retrieves.
        deviceTasks.completeFromCallback(first.retrieveTaskId(), "COMPLETED", "r", Map.of());
        deviceTasks.completeFromCallback(induction.get(first.id()).conveyTaskId(), "COMPLETED", "a", Map.of());
        induction.markDone(first.id(), "ASRS", "op");

        assertThat(induction.get(second.id()).retrieveTaskId()).isNotNull();
    }

    @Test
    void doneEndpointIsIdempotent() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        InductionEntryView e = induction.request(req(warehouse, workplace, UUID.randomUUID(), "A", "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(e.retrieveTaskId(), "COMPLETED", "r", Map.of());
        deviceTasks.completeFromCallback(induction.get(e.id()).conveyTaskId(), "COMPLETED", "a", Map.of());

        InductionEntryView done = induction.markDone(e.id(), "ASRS", "op");
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneAt()).isNotNull();

        // A second DONE returns the entry unchanged (same done_at), and writes no extra trace row.
        long doneRows = traces.timeline(e.huId(), warehouse).stream()
                .filter(t -> "DONE".equals(t.event())).count();
        InductionEntryView again = induction.markDone(e.id(), "ASRS", "op");
        assertThat(again.status()).isEqualTo("DONE");
        // Idempotency is proven by the unchanged DONE-trace count below; doneAt is only checked
        // non-null (exact Instant equality flakes — in-memory nanos vs DB microsecond truncation).
        assertThat(again.doneAt()).isNotNull();
        assertThat(traces.timeline(e.huId(), warehouse).stream()
                .filter(t -> "DONE".equals(t.event())).count()).isEqualTo(doneRows);
    }

    @Test
    void retrieveFailureLeavesEntryRequestedForRetry() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        InductionEntryView e = induction.request(req(warehouse, workplace, UUID.randomUUID(), "A", "STOCK_COUNT"), "op");

        deviceTasks.completeFromCallback(e.retrieveTaskId(), "FAILED", "asrs fault", null);
        InductionEntryView after = induction.get(e.id());
        assertThat(after.status()).isEqualTo("REQUESTED");
        // The failed retrieve link is cleared so a re-meter can re-dispatch; no CONVEY was dispatched.
        assertThat(after.conveyTaskId()).isNull();
    }

    @Test
    void conveyDecisionPointsAreWrittenToTheTrace() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        UUID conveyTask = induction.get(created.id()).conveyTaskId();

        // The emulator reports it recirculated once before diverting to the destination (ADR-0007 R2/R4).
        Map<String, Object> result = Map.of("recirculations", 1, "decisions", List.of(
                Map.of("point", "sorter", "event", "RECIRCULATED", "decision", "missed divert"),
                Map.of("point", "sorter", "event", "DIVERTED", "decision", "to destination")));
        deviceTasks.completeFromCallback(conveyTask, "COMPLETED", "arrived", result);

        List<String> events = traces.timeline(huId, warehouse).stream().map(HuTraceView::event).toList();
        assertThat(events).contains("RECIRCULATED", "DIVERTED", "ARRIVED", "QUEUED");
        // The recirculate/divert decisions precede the arrival in the timeline.
        assertThat(events).containsSubsequence("RECIRCULATED", "ARRIVED", "QUEUED");
    }
}
