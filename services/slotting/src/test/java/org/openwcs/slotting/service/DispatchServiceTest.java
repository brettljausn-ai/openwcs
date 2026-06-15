package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.client.FlowClient;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.InventoryClient.StockBucket;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.ReslotRecommendationRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Dispatching slotting plans to flow as physical moves, over PostgreSQL with flow + clients mocked. */
@SpringBootTest
@Testcontainers
class DispatchServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DispatchService service;
    @Autowired
    ReslotRecommendationRepository recommendations;
    @Autowired
    ReplenishmentTaskRepository tasks;
    @Autowired
    PickSlotRepository pickSlots;
    @Autowired
    StorageProfileRepository profiles;

    @MockBean
    FlowClient flow;
    @MockBean
    InventoryClient inventory;
    @MockBean
    MasterDataClient masterData;

    private static StorageLocation loc(UUID id, String aisle, double dist) {
        return new StorageLocation(id, "L", "STORAGE", "ASRS_SLOT", aisle, 1, 3, null, null, null,
                BigDecimal.valueOf(dist), null, "ACTIVE", null);
    }

    private ReslotRecommendation savedReco(UUID wh, UUID hu, UUID from, UUID to) {
        ReslotRecommendation rec = new ReslotRecommendation();
        rec.setWarehouseId(wh);
        rec.setHuId(hu);
        rec.setSkuId(UUID.randomUUID());
        rec.setFromLocationId(from);
        rec.setToLocationId(to);
        rec.setReason("velocity-fit gain 0.5");
        rec.setStatus("RECOMMENDED");
        return recommendations.save(rec);
    }

    private ReplenishmentTask savedTask(UUID wh, UUID sku, UUID pickFace) {
        ReplenishmentTask task = new ReplenishmentTask();
        task.setWarehouseId(wh);
        task.setSkuId(sku);
        task.setUomId(UUID.randomUUID());
        task.setToLocationId(pickFace);
        task.setQty(new BigDecimal("24"));
        task.setPriority("SCHEDULED");
        task.setTriggerType("BELOW_MIN");
        task.setStatus("PLANNED");
        return tasks.save(task);
    }

    // ---- re-slot dispatch ----

    @Test
    void reslotDispatchCallsFlowWithHuFromToAndFlipsStatus() {
        UUID wh = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID deviceTask = UUID.randomUUID();
        ReslotRecommendation rec = savedReco(wh, hu, from, to);
        when(inventory.handlingUnits(eq(wh))).thenReturn(List.of());
        when(flow.dispatchMove(eq(wh), eq(hu), any(), eq(from), eq(to), eq("RESLOT"), any()))
                .thenReturn(deviceTask);

        ReslotRecommendation out = service.dispatchReslot(rec.getId());

        assertThat(out.getStatus()).isEqualTo("DISPATCHED");
        assertThat(out.getDeviceTaskId()).isEqualTo(deviceTask);
        verify(flow).dispatchMove(eq(wh), eq(hu), any(), eq(from), eq(to), eq("RESLOT"), any());
        assertThat(recommendations.findById(rec.getId()).orElseThrow().getStatus()).isEqualTo("DISPATCHED");
    }

    @Test
    void reslotDispatchRejectsNonRecommended() {
        UUID wh = UUID.randomUUID();
        ReslotRecommendation rec = savedReco(wh, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        rec.setStatus("DISPATCHED");
        recommendations.save(rec);

        assertThatThrownBy(() -> service.dispatchReslot(rec.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(flow, never()).dispatchMove(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reslotDispatchLeavesStatusUnchangedWhenFlowFails() {
        UUID wh = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        ReslotRecommendation rec = savedReco(wh, hu, UUID.randomUUID(), UUID.randomUUID());
        when(inventory.handlingUnits(eq(wh))).thenReturn(List.of());
        when(flow.dispatchMove(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new FlowClient.FlowDispatchException("flow down"));

        assertThatThrownBy(() -> service.dispatchReslot(rec.getId()))
                .isInstanceOf(FlowClient.FlowDispatchException.class);
        assertThat(recommendations.findById(rec.getId()).orElseThrow().getStatus()).isEqualTo("RECOMMENDED");
        assertThat(recommendations.findById(rec.getId()).orElseThrow().getDeviceTaskId()).isNull();
    }

    // ---- replenishment dispatch ----

    @Test
    void replenishmentDispatchResolvesSourceCallsFlowAndFlipsStatus() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID pickFace = UUID.randomUUID();
        UUID reserve = UUID.randomUUID();
        UUID reserveHu = UUID.randomUUID();
        UUID deviceTask = UUID.randomUUID();
        ReplenishmentTask task = savedTask(wh, sku, pickFace);

        // A pick face for the SKU (must be excluded as a source) plus a reserve bucket holding stock.
        PickSlot slot = new PickSlot();
        slot.setWarehouseId(wh);
        slot.setLocationId(pickFace);
        slot.setSkuId(sku);
        slot.setUomId(UUID.randomUUID());
        slot.setMinQty(BigDecimal.ONE);
        slot.setMaxQty(new BigDecimal("48"));
        pickSlots.save(slot);

        when(inventory.stockForSku(eq(wh), eq(sku))).thenReturn(List.of(
                new StockBucket(pickFace, UUID.randomUUID(), null, "AVAILABLE", new BigDecimal("5")), // the face itself
                new StockBucket(reserve, reserveHu, null, "AVAILABLE", new BigDecimal("100"))));        // the reserve
        when(inventory.handlingUnits(eq(wh))).thenReturn(List.of());
        when(flow.dispatchMove(eq(wh), eq(reserveHu), any(), eq(reserve), eq(pickFace), eq("REPLENISHMENT"), any()))
                .thenReturn(deviceTask);

        ReplenishmentTask out = service.dispatchReplenishment(task.getId());

        assertThat(out.getStatus()).isEqualTo("DISPATCHED");
        assertThat(out.getFromLocationId()).isEqualTo(reserve);
        assertThat(out.getDeviceTaskId()).isEqualTo(deviceTask);
        verify(flow).dispatchMove(eq(wh), eq(reserveHu), any(), eq(reserve), eq(pickFace), eq("REPLENISHMENT"), any());
    }

    @Test
    void replenishmentDispatchUsesBlankSourceWhenUnresolvable() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID pickFace = UUID.randomUUID();
        UUID deviceTask = UUID.randomUUID();
        ReplenishmentTask task = savedTask(wh, sku, pickFace);
        when(inventory.stockForSku(eq(wh), eq(sku))).thenReturn(List.of()); // nothing to draw from
        when(inventory.handlingUnits(eq(wh))).thenReturn(List.of());
        when(flow.dispatchMove(eq(wh), isNull(), any(), isNull(), eq(pickFace), eq("REPLENISHMENT"), any()))
                .thenReturn(deviceTask);

        ReplenishmentTask out = service.dispatchReplenishment(task.getId());

        assertThat(out.getStatus()).isEqualTo("DISPATCHED");
        assertThat(out.getFromLocationId()).isNull();
        verify(flow).dispatchMove(eq(wh), isNull(), any(), isNull(), eq(pickFace), eq("REPLENISHMENT"), any());
    }

    @Test
    void replenishmentDispatchRejectsNonPlanned() {
        UUID wh = UUID.randomUUID();
        ReplenishmentTask task = savedTask(wh, UUID.randomUUID(), UUID.randomUUID());
        task.setStatus("DISPATCHED");
        tasks.save(task);

        assertThatThrownBy(() -> service.dispatchReplenishment(task.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(flow, never()).dispatchMove(any(), any(), any(), any(), any(), any(), any());
    }

    // ---- decant -> put-away ----

    @Test
    void decantPutawayRunsScorerThenCallsFlow() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID hu = UUID.randomUUID();
        UUID station = UUID.randomUUID();
        UUID slotA = UUID.randomUUID();
        UUID deviceTask = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profile.setVelocityClass("A");
        profile.setMaxAislePct(BigDecimal.ONE);
        profiles.save(profile);

        when(masterData.storageLocations(eq(wh), eq(block))).thenReturn(List.of(loc(slotA, "A1", 1.0)));
        when(masterData.block(eq(block))).thenReturn(null);
        when(inventory.occupiedLocations(any(), any())).thenReturn(java.util.Set.of());
        when(inventory.handlingUnits(eq(wh))).thenReturn(List.of());
        when(flow.dispatchMove(eq(wh), eq(hu), any(), eq(station), eq(slotA), eq("PUTAWAY"), any()))
                .thenReturn(deviceTask);

        DispatchService.DecantResult result =
                service.dispatchDecantPutaway(wh, hu, sku, new BigDecimal("10"), station);

        assertThat(result.decision().locationId()).isEqualTo(slotA);
        assertThat(result.deviceTaskId()).isEqualTo(deviceTask);
        verify(flow).dispatchMove(eq(wh), eq(hu), any(), eq(station), eq(slotA), eq("PUTAWAY"), any());
    }
}
