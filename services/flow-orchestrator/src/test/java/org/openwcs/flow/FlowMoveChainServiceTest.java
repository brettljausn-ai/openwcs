package org.openwcs.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.flow.api.DeviceTaskView;
import org.openwcs.flow.api.FlowMoveRequest;
import org.openwcs.flow.api.FlowMoveResult;
import org.openwcs.flow.client.DeviceClient;
import org.openwcs.flow.client.InventoryClient;
import org.openwcs.flow.client.MasterDataClient;
import org.openwcs.flow.service.DeviceTaskService;
import org.openwcs.flow.service.FlowMoveService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the flow-orchestrator against PostgreSQL 16 with the device adapter, inventory and
 * master-data lookups mocked. Exercises {@code POST /api/flow/moves} transport selection:
 *
 * <ul>
 *   <li>a SAME-system move (both ends in one storage block) still dispatches a single RELOCATE;</li>
 *   <li>a CROSS-system move dispatches RETRIEVE first, then on its completion a CONVEY, then on its
 *       completion a STORE, and the final STORE completion books the destination location;</li>
 *   <li>a failed leg stops the chain (status FAILED) and dispatches nothing further;</li>
 *   <li>each leg advances exactly once (a duplicate callback is a no-op).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class FlowMoveChainServiceTest {

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
    InventoryClient inventory;

    @MockBean
    MasterDataClient masterData;

    @Autowired
    FlowMoveService moveService;

    @Autowired
    DeviceTaskService deviceTasks;

    private void asyncAdapter() {
        // Every dispatched device task is accepted (async): it stays DISPATCHED until we post the
        // terminal callback ourselves, mirroring the emulator's ack-then-callback behaviour.
        when(deviceClient.execute(any())).thenReturn(
                new DeviceClient.DeviceResult("ACCEPTED", "dispatched", null));
    }

    /** from and to share one storage block ⇒ same-system. */
    private void sameSystemBlocks(UUID warehouse, UUID from, UUID to) {
        UUID block = UUID.randomUUID();
        when(masterData.blockId(warehouse, from)).thenReturn(block);
        when(masterData.blockId(warehouse, to)).thenReturn(block);
    }

    /** from and to live in different storage blocks ⇒ cross-system. */
    private void crossSystemBlocks(UUID warehouse, UUID from, UUID to) {
        when(masterData.blockId(warehouse, from)).thenReturn(UUID.randomUUID());
        when(masterData.blockId(warehouse, to)).thenReturn(UUID.randomUUID());
    }

    @Test
    void sameSystemMoveDispatchesASingleRelocate() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        sameSystemBlocks(warehouse, from, to);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-1", from, to, "ASRS", null, null, "rebalance"), "op");

        DeviceTaskView task = deviceTasks.get(result.deviceTaskId());
        assertThat(task.command()).isEqualTo("RELOCATE");
        assertThat(task.family()).isEqualTo("ASRS");
        assertThat(task.payload()).containsEntry("moveSource", "flow-move")
                .containsEntry("toLocationId", to.toString());
        // Exactly one device task was dispatched (no chain legs).
        verify(deviceClient, times(1)).execute(any());

        // Completion books the destination via the same-system flow-move booking path.
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "relocated", Map.of());
        verify(inventory, times(1)).bookLocation(huId, to);
    }

    @Test
    void crossSystemMoveRunsRetrieveThenConveyThenStoreAndBooksDestination() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        crossSystemBlocks(warehouse, from, to);

        // Source family AUTOSTORE ⇒ BIN_RETRIEVE; explicit destFamily ASRS ⇒ STORE on the store leg.
        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-9", from, to, "AUTOSTORE", "ASRS", null, "rebalance"),
                "op");

        // Leg 1: the first dispatched leg is the RETRIEVE.
        DeviceTaskView retrieve = deviceTasks.get(result.deviceTaskId());
        assertThat(retrieve.command()).isEqualTo("BIN_RETRIEVE");
        assertThat(retrieve.family()).isEqualTo("AUTOSTORE");
        assertThat(retrieve.payload()).containsEntry("huId", huId.toString())
                .containsEntry("locationId", from.toString());
        // No location booked and no CONVEY/STORE yet — only the RETRIEVE is out.
        verify(inventory, never()).bookLocation(any(), any());
        verify(deviceClient, times(1)).execute(any());

        // Leg 2: RETRIEVE completes ⇒ a CONVEY is dispatched.
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "retrieved", Map.of());
        verify(deviceClient, times(2)).execute(any());
        DeviceTaskView convey = lastTask(warehouse, "CONVEY");
        assertThat(convey.family()).isEqualTo("CONVEYOR");
        assertThat(convey.payload()).containsEntry("destinationLocationId", to.toString());

        // Leg 3: CONVEY completes ⇒ a STORE is dispatched into the destination.
        deviceTasks.completeFromCallback(convey.id(), "COMPLETED", "conveyed", Map.of());
        verify(deviceClient, times(3)).execute(any());
        DeviceTaskView store = lastTask(warehouse, "STORE");
        assertThat(store.command()).isEqualTo("STORE");
        assertThat(store.family()).isEqualTo("ASRS");
        assertThat(store.payload()).containsEntry("locationId", to.toString());
        // The destination is NOT booked until the STORE completes.
        verify(inventory, never()).bookLocation(any(), any());

        // Final: STORE completes ⇒ the HU is booked into the destination location exactly once.
        deviceTasks.completeFromCallback(store.id(), "COMPLETED", "stored", Map.of());
        verify(inventory, times(1)).bookLocation(huId, to);

        // A duplicate STORE callback must not re-book (idempotent terminal).
        deviceTasks.completeFromCallback(store.id(), "COMPLETED", "again", Map.of());
        verify(inventory, times(1)).bookLocation(huId, to);
        // No further device tasks were dispatched (exactly the three legs).
        verify(deviceClient, times(3)).execute(any());
    }

    @Test
    void crossSystemMoveIntoAutostoreDestResolvesBinStoreEvenWhenSourceFamilyDiffers() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID toBlock = UUID.randomUUID();
        // Source is ASRS; destination block resolves to AUTOSTORE ⇒ the STORE leg must be BIN_STORE
        // (AUTOSTORE family), NOT the source family, with no explicit destFamily on the request.
        when(masterData.blockId(warehouse, from)).thenReturn(UUID.randomUUID());
        when(masterData.blockId(warehouse, to)).thenReturn(toBlock);
        when(masterData.blockStorageType(warehouse, toBlock)).thenReturn("AUTOSTORE");

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-AS", from, to, "ASRS", null, null, "rebalance"), "op");

        // Leg 1: source family ASRS ⇒ plain RETRIEVE.
        assertThat(deviceTasks.get(result.deviceTaskId()).command()).isEqualTo("RETRIEVE");
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "retrieved", Map.of());
        UUID convey = lastTask(warehouse, "CONVEY").id();
        deviceTasks.completeFromCallback(convey, "COMPLETED", "conveyed", Map.of());

        // Leg 3: the destination block resolved AUTOSTORE ⇒ BIN_STORE on the AUTOSTORE family.
        DeviceTaskView store = lastTask(warehouse, "BIN_STORE");
        assertThat(store.command()).isEqualTo("BIN_STORE");
        assertThat(store.family()).isEqualTo("AUTOSTORE");
        assertThat(store.payload()).containsEntry("locationId", to.toString());
    }

    @Test
    void crossSystemMoveWithUnresolvableDestBlockFallsBackToSourceFamily() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID toBlock = UUID.randomUUID();
        // Destination block exists but its storage type can't be mapped to a device family (master-data
        // down / non-automated) ⇒ the STORE leg falls back to the source family (AUTOSTORE ⇒ BIN_STORE).
        when(masterData.blockId(warehouse, from)).thenReturn(UUID.randomUUID());
        when(masterData.blockId(warehouse, to)).thenReturn(toBlock);
        when(masterData.blockStorageType(warehouse, toBlock)).thenReturn(null);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-FB", from, to, "AUTOSTORE", null, null, null), "op");

        // Source family AUTOSTORE ⇒ BIN_RETRIEVE on leg 1.
        assertThat(deviceTasks.get(result.deviceTaskId()).command()).isEqualTo("BIN_RETRIEVE");
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "retrieved", Map.of());
        UUID convey = lastTask(warehouse, "CONVEY").id();
        deviceTasks.completeFromCallback(convey, "COMPLETED", "conveyed", Map.of());

        // Fallback to the source AUTOSTORE family ⇒ BIN_STORE.
        DeviceTaskView store = lastTask(warehouse, "BIN_STORE");
        assertThat(store.family()).isEqualTo("AUTOSTORE");
    }

    @Test
    void destinationWithoutABlockIsCrossSystem() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // A storage slot (has a block) -> a conveyor/workplace operational location (no block).
        when(masterData.blockId(warehouse, from)).thenReturn(UUID.randomUUID());
        when(masterData.blockId(warehouse, to)).thenReturn(null);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-2", from, to, "ASRS", null, null, null), "op");

        assertThat(deviceTasks.get(result.deviceTaskId()).command()).isEqualTo("RETRIEVE");
    }

    @Test
    void unresolvedSourceBlockStaysSameSystem() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        // master-data can't resolve the source block (e.g. down): keep today's single-RELOCATE path.
        when(masterData.blockId(warehouse, from)).thenReturn(null);
        when(masterData.blockId(warehouse, to)).thenReturn(UUID.randomUUID());

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-3", from, to, "ASRS", null, null, null), "op");

        assertThat(deviceTasks.get(result.deviceTaskId()).command()).isEqualTo("RELOCATE");
    }

    @Test
    void crossSystemFlagForcesTheChainEvenWithinOneBlock() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        sameSystemBlocks(warehouse, from, to); // same block, but the flag forces the chain

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-4", from, to, "ASRS", null, true, null), "op");

        assertThat(deviceTasks.get(result.deviceTaskId()).command()).isEqualTo("RETRIEVE");
    }

    @Test
    void failedRetrieveLegStopsTheChainAndDispatchesNothingFurther() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        crossSystemBlocks(warehouse, from, to);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-5", from, to, "ASRS", null, null, null), "op");
        verify(deviceClient, times(1)).execute(any());

        // The RETRIEVE fails: the chain marks FAILED, NO CONVEY is dispatched, NO booking happens.
        deviceTasks.completeFromCallback(result.deviceTaskId(), "FAILED", "asrs fault", null);
        verify(deviceClient, times(1)).execute(any());
        verify(inventory, never()).bookLocation(any(), any());
    }

    @Test
    void failedStoreLegDoesNotBookTheDestination() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        crossSystemBlocks(warehouse, from, to);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-6", from, to, "ASRS", null, null, null), "op");
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "retrieved", Map.of());
        UUID convey = lastTask(warehouse, "CONVEY").id();
        deviceTasks.completeFromCallback(convey, "COMPLETED", "conveyed", Map.of());
        UUID store = lastTask(warehouse, "STORE").id();

        // The STORE fails: no destination booking, the chain stops at FAILED.
        deviceTasks.completeFromCallback(store, "FAILED", "store fault", null);
        verify(inventory, never()).bookLocation(eq(huId), eq(to));
    }

    @Test
    void duplicateRetrieveCallbackDoesNotDispatchTheConveyTwice() {
        asyncAdapter();
        UUID warehouse = UUID.randomUUID();
        UUID huId = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        crossSystemBlocks(warehouse, from, to);

        FlowMoveResult result = moveService.move(
                new FlowMoveRequest(warehouse, huId, "TTE-8", from, to, "ASRS", null, null, null), "op");
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "retrieved", Map.of());
        // A duplicate terminal callback for the same RETRIEVE is a no-op (DeviceTaskService guards it).
        deviceTasks.completeFromCallback(result.deviceTaskId(), "COMPLETED", "again", Map.of());

        // Exactly two tasks dispatched: the RETRIEVE and a single CONVEY.
        verify(deviceClient, times(2)).execute(any());
    }

    /** The most recently dispatched device task of a given command in this warehouse. */
    private DeviceTaskView lastTask(UUID warehouse, String command) {
        return deviceTasks.search(warehouse, null, null, null, 100).stream()
                .filter(t -> command.equals(t.command()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + command + " task dispatched"));
    }
}
