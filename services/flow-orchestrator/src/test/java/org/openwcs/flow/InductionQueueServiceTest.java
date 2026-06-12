package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.HuTraceView;
import org.openwcs.flow.api.InductionEntryView;
import org.openwcs.flow.api.InductionRequest;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.client.InventoryClient;
import org.openwcs.flow.client.MasterDataClient;
import org.openwcs.flow.client.SlottingClient;
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

    @MockBean
    InventoryClient inventoryClient;

    @MockBean
    SlottingClient slottingClient;

    @MockBean
    MasterDataClient masterDataClient;

    @Autowired
    InductionQueueService induction;

    @Autowired
    DeviceTaskService deviceTasks;

    @Autowired
    HuTraceService traces;

    @BeforeEach
    void clearChannelByDefault() {
        // ADR-0009: every test runs against a clear channel (empty plan, not blocked) unless it
        // stubs a dig-out plan itself — the pre-0009 behaviour stays byte-for-byte unchanged.
        when(slottingClient.plan(any(), any())).thenReturn(emptyPlan(false));
    }

    private static SlottingClient.RelocationPlan emptyPlan(boolean blocked) {
        return new SlottingClient.RelocationPlan(List.of(), blocked);
    }

    private static SlottingClient.RelocationPlan planOf(SlottingClient.RelocationStep... steps) {
        return new SlottingClient.RelocationPlan(List.of(steps), false);
    }

    /** Slotting answers the return-leg put-away with the given location. */
    private UUID slotAnswer() {
        UUID slot = UUID.randomUUID();
        when(slottingClient.bestLocation(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(slot));
        return slot;
    }

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

    /** Drive an entry through REQUESTED → IN_TRANSIT → QUEUED via the device-task callbacks. */
    private InductionEntryView driveToQueued(UUID warehouse, UUID workplace, UUID huId, String huCode) {
        InductionEntryView created = induction.request(req(warehouse, workplace, huId, huCode, "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        deviceTasks.completeFromCallback(induction.get(created.id()).conveyTaskId(), "COMPLETED", "arrived", Map.of());
        return induction.get(created.id());
    }

    @Test
    void markDoneDispatchesReturnConveyExactlyOnce() {
        asyncAdapter();
        cap(5, 5);
        UUID slot = slotAnswer();
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView queued = driveToQueued(warehouse, workplace, huId, "TOTE-1");

        InductionEntryView done = induction.markDone(queued.id(), "ASRS", "op");
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.returnConveyTaskId()).isNotNull();
        assertThat(done.returnStoreTaskId()).isNull(); // STORE only after the return convey arrives
        assertThat(done.storageLocationId()).isEqualTo(slot); // slotting decided the destination
        assertThat(done.awaitingSlot()).isFalse();

        // The return CONVEY carries the station as source and the SLOTTING-chosen destination —
        // never the source slot.
        DeviceTaskView returnConvey = deviceTasks.get(done.returnConveyTaskId());
        assertThat(returnConvey.command()).isEqualTo("CONVEY");
        assertThat(returnConvey.family()).isEqualTo("CONVEYOR");
        assertThat(returnConvey.payload()).containsEntry("sourceWorkplaceId", workplace.toString());
        assertThat(returnConvey.payload()).containsEntry("destinationLocationId", slot.toString());
        assertThat(returnConvey.payload()).doesNotContainKey("returnLocationId");

        // The RETURNING trace row is written once, with the station as from_point.
        assertThat(traces.timeline(huId, warehouse)).extracting(HuTraceView::event).contains("RETURNING");

        // A second DONE is a no-op: same return convey task, no second CONVEY dispatched.
        InductionEntryView again = induction.markDone(queued.id(), "ASRS", "op");
        assertThat(again.returnConveyTaskId()).isEqualTo(done.returnConveyTaskId());
        long returnConveys = deviceTasks.byCorrelation(huId).stream()
                .filter(t -> "CONVEY".equals(t.command()) && t.payload().containsKey("sourceWorkplaceId"))
                .count();
        assertThat(returnConveys).isEqualTo(1);
        assertThat(traces.timeline(huId, warehouse).stream()
                .filter(t -> "RETURNING".equals(t.event())).count()).isEqualTo(1);
    }

    @Test
    void returnConveyCompletionDispatchesStoreIntoTheSlottingChosenLocation() {
        asyncAdapter();
        cap(5, 5);
        UUID slot = slotAnswer();
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView done = induction.markDone(
                driveToQueued(warehouse, workplace, huId, "TOTE-1").id(), "ASRS", "op");

        // Return CONVEY completes (= arrival back at storage) -> STORE dispatched + RETURN_ARRIVED traced.
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "arrived", Map.of());
        InductionEntryView after = induction.get(done.id());
        assertThat(after.returnStoreTaskId()).isNotNull();

        DeviceTaskView store = deviceTasks.get(after.returnStoreTaskId());
        assertThat(store.command()).isEqualTo("STORE"); // default family ASRS -> STORE (not BIN_STORE)
        assertThat(store.family()).isEqualTo("ASRS");
        // The STORE goes into the slotting-chosen location, NOT the entry's source slot.
        assertThat(store.payload()).containsEntry("locationId", slot.toString());
        assertThat(store.payload().get("locationId")).isNotEqualTo(after.locationId().toString());

        assertThat(traces.timeline(huId, warehouse)).extracting(HuTraceView::event).contains("RETURN_ARRIVED");
    }

    @Test
    void storeCompletionWritesStoredTrace() {
        asyncAdapter();
        cap(5, 5);
        UUID slot = slotAnswer();
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView done = induction.markDone(
                driveToQueued(warehouse, workplace, huId, "TOTE-1").id(), "ASRS", "op");
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "arrived", Map.of());

        // STORE completes -> STORED trace at the slotting-chosen slot closes the HU's timeline.
        deviceTasks.completeFromCallback(induction.get(done.id()).returnStoreTaskId(), "COMPLETED", "stored", Map.of());
        List<HuTraceView> timeline = traces.timeline(huId, warehouse);
        assertThat(timeline).extracting(HuTraceView::event)
                .containsSubsequence("DONE", "RETURN_ARRIVED", "STORED");
        HuTraceView stored = timeline.stream().filter(t -> "STORED".equals(t.event())).findFirst().orElseThrow();
        assertThat(stored.point()).isEqualTo("slot:" + slot);
        assertThat(stored.decision()).isEqualTo("stored to slotting-chosen location");
    }

    @Test
    void retrieveCompletionBooksTheHuOutOfItsSlot() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());

        // The tote left its slot: the HU registry is booked to locationId = null (in transit).
        verify(inventoryClient).bookLocation(huId, null);
    }

    @Test
    void returnStoreCompletionBooksTheHuIntoTheSlottingChosenLocation() {
        asyncAdapter();
        cap(5, 5);
        UUID slot = slotAnswer();
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView queued = driveToQueued(warehouse, workplace, huId, "TOTE-1");
        UUID sourceSlot = queued.locationId();
        assertThat(sourceSlot).isNotNull();

        InductionEntryView done = induction.markDone(queued.id(), "ASRS", "op");
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "arrived", Map.of());
        deviceTasks.completeFromCallback(induction.get(done.id()).returnStoreTaskId(), "COMPLETED", "stored", Map.of());

        // The tote is physically stored: the HU registry is booked into the SLOTTING-chosen slot —
        // and never back into the source slot.
        verify(inventoryClient).bookLocation(huId, slot);
        verify(inventoryClient, org.mockito.Mockito.never()).bookLocation(huId, sourceSlot);
    }

    @Test
    void slottingFailureLeavesTheToteOnTheConveyorAwaitingSlot() {
        asyncAdapter();
        cap(5, 5);
        // Slotting errors (e.g. 400 "no storage profile / block for sku"): NO source-slot fallback.
        when(slottingClient.bestLocation(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("400 no storage profile / block for sku"));
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView queued = driveToQueued(warehouse, workplace, huId, "TOTE-1");

        InductionEntryView done = induction.markDone(queued.id(), "ASRS", "op");

        // The return CONVEY is dispatched anyway (the tote must leave the workplace) ...
        assertThat(done.returnConveyTaskId()).isNotNull();
        DeviceTaskView returnConvey = deviceTasks.get(done.returnConveyTaskId());
        // ... but with NO storage destination and NO route plan: the tote stays on the conveyor.
        assertThat(returnConvey.payload())
                .doesNotContainKeys("destinationLocationId", "returnLocationId", "entryNode",
                        "destinationNode");
        assertThat(done.awaitingSlot()).isTrue();
        assertThat(done.storageLocationId()).isNull();

        // Even when the CONVEY completes, NO STORE goes out while the slot is missing — and the
        // source slot is never used.
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "circulating", Map.of());
        assertThat(induction.get(done.id()).returnStoreTaskId()).isNull();
    }

    @Test
    void retrySweepAssignsSlotAndStoresAfterTheConveyAlreadyCompleted() {
        asyncAdapter();
        cap(5, 5);
        when(slottingClient.bestLocation(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty()); // no answer yet
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView done = induction.markDone(
                driveToQueued(warehouse, workplace, huId, "TOTE-1").id(), "ASRS", "op");
        assertThat(done.awaitingSlot()).isTrue();
        // The plan-less return CONVEY completes while the tote is still slot-less: no STORE.
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "circulating", Map.of());
        assertThat(induction.get(done.id()).returnStoreTaskId()).isNull();

        // A sweep while slotting still has no answer changes nothing.
        induction.retryAwaitingSlots("sweep");
        assertThat(induction.get(done.id()).awaitingSlot()).isTrue();

        // Slotting finally answers: the sweep stamps the destination and fires the STORE directly
        // (the arrival callback already came and went).
        UUID slot = slotAnswer();
        induction.retryAwaitingSlots("sweep");
        InductionEntryView after = induction.get(done.id());
        assertThat(after.awaitingSlot()).isFalse();
        assertThat(after.storageLocationId()).isEqualTo(slot);
        assertThat(after.returnStoreTaskId()).isNotNull();
        assertThat(deviceTasks.get(after.returnStoreTaskId()).payload())
                .containsEntry("locationId", slot.toString());
    }

    @Test
    void retrySweepAssignsSlotMidJourneyAndArrivalDispatchesTheStore() {
        asyncAdapter();
        cap(5, 5);
        when(slottingClient.bestLocation(any(), any(), any(), any()))
                .thenReturn(java.util.Optional.empty()); // no answer at completion time
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        InductionEntryView done = induction.markDone(
                driveToQueued(warehouse, workplace, huId, "TOTE-1").id(), "ASRS", "op");
        assertThat(done.awaitingSlot()).isTrue();

        // Slotting answers while the tote is still walking: destination stamped, no STORE yet.
        UUID slot = slotAnswer();
        induction.retryAwaitingSlots("sweep");
        InductionEntryView assigned = induction.get(done.id());
        assertThat(assigned.awaitingSlot()).isFalse();
        assertThat(assigned.storageLocationId()).isEqualTo(slot);
        assertThat(assigned.returnStoreTaskId()).isNull();
        assertThat(traces.timeline(huId, warehouse)).extracting(HuTraceView::event)
                .contains("SLOT_ASSIGNED");

        // The return CONVEY then arrives: the existing arrival -> STORE wiring fires into the slot.
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "arrived", Map.of());
        InductionEntryView after = induction.get(done.id());
        assertThat(after.returnStoreTaskId()).isNotNull();
        assertThat(deviceTasks.get(after.returnStoreTaskId()).payload())
                .containsEntry("locationId", slot.toString());
    }

    @Test
    void conveyDispatchAndQueuedBookOperationalLocations() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID workplaceLocation = UUID.randomUUID();
        // gtp knows the workplace code; master-data resolves its operational location.
        when(workplaceClient.code(workplace)).thenReturn(java.util.Optional.of("PP1"));
        when(masterDataClient.operationalLocation(warehouse, "WORKPLACE", "PP1"))
                .thenReturn(workplaceLocation);

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        // Un-projected warehouse: no entry node resolves, so the CONVEY dispatch books null (UNKNOWN).
        verify(inventoryClient).bookLocation(huId, null);

        // Arrival at the workplace (QUEUED) books the WORKPLACE's operational location.
        deviceTasks.completeFromCallback(induction.get(created.id()).conveyTaskId(), "COMPLETED", "arrived", Map.of());
        verify(inventoryClient).bookLocation(huId, workplaceLocation);
    }

    @Test
    void locationBookingFailureNeverBreaksThePipeline() {
        asyncAdapter();
        cap(5, 5);
        slotAnswer();
        doThrow(new RuntimeException("inventory down")).when(inventoryClient).bookLocation(any(), any());
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();

        // Retrieve completion still advances to IN_TRANSIT + dispatches the CONVEY leg.
        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        deviceTasks.completeFromCallback(created.retrieveTaskId(), "COMPLETED", "retrieved", Map.of());
        InductionEntryView inTransit = induction.get(created.id());
        assertThat(inTransit.status()).isEqualTo("IN_TRANSIT");
        assertThat(inTransit.conveyTaskId()).isNotNull();

        // ... and the return-leg STORE completion still writes the STORED trace.
        deviceTasks.completeFromCallback(inTransit.conveyTaskId(), "COMPLETED", "arrived", Map.of());
        InductionEntryView done = induction.markDone(created.id(), "ASRS", "op");
        deviceTasks.completeFromCallback(done.returnConveyTaskId(), "COMPLETED", "arrived", Map.of());
        deviceTasks.completeFromCallback(induction.get(done.id()).returnStoreTaskId(), "COMPLETED", "stored", Map.of());
        assertThat(traces.timeline(huId, warehouse)).extracting(HuTraceView::event).contains("STORED");
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

    // ---- ADR-0009: dig-out RELOCATE chain before a blocked retrieve ----------------------------

    @Test
    void blockedChannelRelocatesTheBlockerBeforeTheRetrieve() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID blockerHuId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // First plan: one blocker in front of the tote; after its move the channel is clear.
        when(slottingClient.plan(any(), any())).thenReturn(
                planOf(new SlottingClient.RelocationStep(blockerHuId, "BLOCKER-1", from, to)),
                emptyPlan(false));

        InductionEntryView created = induction.request(req(warehouse, workplace, huId, "TOTE-1", "STOCK_COUNT"), "op");
        // The dig-out goes out first: RELOCATE stamped, NO retrieve dispatched yet.
        assertThat(created.relocateTaskId()).isNotNull();
        assertThat(created.retrieveTaskId()).isNull();
        DeviceTaskView relocate = deviceTasks.get(created.relocateTaskId());
        assertThat(relocate.command()).isEqualTo("RELOCATE");
        assertThat(relocate.family()).isEqualTo("ASRS");
        assertThat(relocate.payload())
                .containsEntry("huId", blockerHuId.toString())
                .containsEntry("huCode", "BLOCKER-1")
                .containsEntry("fromLocationId", from.toString())
                .containsEntry("toLocationId", to.toString())
                .containsEntry("forHuId", huId.toString());

        // The RELOCATE completes: the BLOCKER's new location is booked, RELOCATED is traced for the
        // blocker, and the re-plan (channel now clear) finally dispatches the RETRIEVE.
        deviceTasks.completeFromCallback(created.relocateTaskId(), "COMPLETED", "relocated", Map.of());
        verify(inventoryClient).bookLocation(blockerHuId, to);
        InductionEntryView after = induction.get(created.id());
        assertThat(after.relocateTaskId()).isNull();
        assertThat(after.retrieveTaskId()).isNotNull();
        assertThat(after.status()).isEqualTo("REQUESTED"); // only the RETRIEVE callback advances it

        HuTraceView relocated = traces.timeline(blockerHuId, warehouse).stream()
                .filter(t -> "RELOCATED".equals(t.event())).findFirst().orElseThrow();
        assertThat(relocated.huCode()).isEqualTo("BLOCKER-1");
        assertThat(relocated.point()).isEqualTo("slot:" + to);
        assertThat(relocated.fromPoint()).isEqualTo("slot:" + from);
        assertThat(relocated.decision()).isEqualTo("relocated out of channel for TOTE-1");
    }

    @Test
    void twoBlockerChainRelocatesEachBeforeTheRetrieve() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        UUID blocker1 = UUID.randomUUID();
        UUID blocker2 = UUID.randomUUID();
        // Each completed relocate re-plans (stateless, self-healing): the second step is returned
        // exactly once, then the channel is clear.
        when(slottingClient.plan(any(), any())).thenReturn(
                planOf(new SlottingClient.RelocationStep(blocker1, "B-1", UUID.randomUUID(), UUID.randomUUID())),
                planOf(new SlottingClient.RelocationStep(blocker2, "B-2", UUID.randomUUID(), UUID.randomUUID())),
                emptyPlan(false));

        InductionEntryView created = induction.request(
                req(warehouse, workplace, UUID.randomUUID(), "TOTE-1", "STOCK_COUNT"), "op");
        UUID firstRelocate = created.relocateTaskId();
        assertThat(firstRelocate).isNotNull();
        assertThat(created.retrieveTaskId()).isNull();

        // First blocker done -> the re-plan still finds a blocker -> a SECOND relocate, still no retrieve.
        deviceTasks.completeFromCallback(firstRelocate, "COMPLETED", "relocated", Map.of());
        InductionEntryView afterFirst = induction.get(created.id());
        assertThat(afterFirst.relocateTaskId()).isNotNull().isNotEqualTo(firstRelocate);
        assertThat(afterFirst.retrieveTaskId()).isNull();

        // Second blocker done -> channel clear -> the original RETRIEVE goes out.
        deviceTasks.completeFromCallback(afterFirst.relocateTaskId(), "COMPLETED", "relocated", Map.of());
        InductionEntryView afterSecond = induction.get(created.id());
        assertThat(afterSecond.relocateTaskId()).isNull();
        assertThat(afterSecond.retrieveTaskId()).isNotNull();
    }

    @Test
    void blockedButUnplannableChannelDegradesToDirectRetrieve() {
        asyncAdapter();
        cap(5, 5);
        // blocked=true with no steps: slotting can't plan the dig-out — flow warns and retrieves
        // as today (the emulator doesn't enforce blocking).
        when(slottingClient.plan(any(), any())).thenReturn(emptyPlan(true));

        InductionEntryView created = induction.request(
                req(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TOTE-1", "STOCK_COUNT"), "op");
        assertThat(created.retrieveTaskId()).isNotNull();
        assertThat(created.relocateTaskId()).isNull();
    }

    @Test
    void relocateFailureLeavesEntryRequestedAndRetryable() {
        asyncAdapter();
        cap(5, 5);
        UUID warehouse = UUID.randomUUID();
        UUID workplace = UUID.randomUUID();
        when(slottingClient.plan(any(), any())).thenReturn(
                planOf(new SlottingClient.RelocationStep(UUID.randomUUID(), "B-1",
                        UUID.randomUUID(), UUID.randomUUID())));

        InductionEntryView created = induction.request(
                req(warehouse, workplace, UUID.randomUUID(), "TOTE-1", "STOCK_COUNT"), "op");
        UUID failedRelocate = created.relocateTaskId();
        assertThat(failedRelocate).isNotNull();

        // The RELOCATE fails: the task link is cleared and the entry stays REQUESTED (no retrieve,
        // no inventory booking) — mirroring failed-retrieve semantics.
        deviceTasks.completeFromCallback(failedRelocate, "FAILED", "shuttle fault", null);
        InductionEntryView after = induction.get(created.id());
        assertThat(after.status()).isEqualTo("REQUESTED");
        assertThat(after.relocateTaskId()).isNull();
        assertThat(after.retrieveTaskId()).isNull();
        verify(inventoryClient, org.mockito.Mockito.never()).bookLocation(any(), any());

        // ... and the next meter pass retries the dig-out with a fresh RELOCATE.
        induction.meterRetrievals(workplace, "ASRS", "op");
        assertThat(induction.get(created.id()).relocateTaskId()).isNotNull().isNotEqualTo(failedRelocate);
    }

    @Test
    void slottingClientFailureDegradesToDirectRetrieve() {
        asyncAdapter();
        cap(5, 5);
        // The plan lookup blows up: the chain must degrade to today's direct retrieve, never block.
        when(slottingClient.plan(any(), any())).thenThrow(new RuntimeException("slotting down"));

        InductionEntryView created = induction.request(
                req(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TOTE-1", "STOCK_COUNT"), "op");
        assertThat(created.retrieveTaskId()).isNotNull();
        assertThat(created.relocateTaskId()).isNull();
    }
}
