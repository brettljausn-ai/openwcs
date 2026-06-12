package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.api.PutawayDecision;
import org.openwcs.slotting.api.PutawayRequest;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end put-away over PostgreSQL with the master-data / inventory clients mocked. */
@SpringBootTest
@Testcontainers
class PutawayServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    PutawayService service;

    @Autowired
    StorageProfileRepository profiles;

    @Autowired
    PickSlotRepository pickSlots;

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    private static StorageLocation loc(UUID id, String aisle, int laneDepth, double dist) {
        return loc(id, aisle, laneDepth, dist, null);
    }

    private static StorageLocation loc(UUID id, String aisle, int laneDepth, double dist, List<String> allowedHuTypes) {
        return new StorageLocation(id, "L-" + id.toString().substring(0, 4), "STORAGE", "ASRS_SLOT",
                aisle, 1, laneDepth, null, null, null, BigDecimal.valueOf(dist), null, "ACTIVE", allowedHuTypes);
    }

    @Test
    void reservePlacementPicksTheBestScoredLocationAndPersists() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profile.setVelocityClass("A"); // fast mover → nearest exit
        profiles.save(profile);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(loc(far, "A1", 3, 10.0), loc(near, "A1", 3, 1.0)));

        PutawayDecision decision = service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, null, BigDecimal.ONE, null, null, false, null));

        assertThat(decision.mode()).isEqualTo("RESERVE");
        assertThat(decision.blockId()).isEqualTo(block);
        assertThat(decision.locationId()).isEqualTo(near);
        assertThat(decision.assignmentId()).isNotNull();
    }

    @Test
    void skipsPhysicallyOccupiedLocationEvenWithoutAnAssignment() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profile.setVelocityClass("A"); // fast mover → would prefer the nearest exit
        profiles.save(profile);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(loc(far, "A1", 3, 10.0), loc(near, "A1", 3, 1.0)));
        // The preferred (near) slot physically holds stock/HU despite having no slotting assignment.
        when(inventory.occupiedLocations(eq(wh), any())).thenReturn(java.util.Set.of(near));

        PutawayDecision decision = service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, null, BigDecimal.ONE, null, null, false, null));

        assertThat(decision.mode()).isEqualTo("RESERVE");
        assertThat(decision.locationId()).isEqualTo(far);   // near is occupied → fall back to far
    }

    @Test
    void directToPickWhenForwardFaceHasHeadroom() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID uom = UUID.randomUUID();
        UUID faceLoc = UUID.randomUUID();

        PickSlot slot = new PickSlot();
        slot.setWarehouseId(wh);
        slot.setLocationId(faceLoc);
        slot.setSkuId(sku);
        slot.setUomId(uom);
        slot.setMinQty(new BigDecimal("12"));
        slot.setMaxQty(new BigDecimal("48"));
        slot.setDirectToPick(true);
        pickSlots.save(slot);

        when(inventory.onHandAtLocation(any(), any(), any())).thenReturn(new BigDecimal("10"));

        PutawayDecision decision = service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, uom, BigDecimal.ONE, null, null, false, null));

        assertThat(decision.mode()).isEqualTo("DIRECT_TO_PICK");
        assertThat(decision.locationId()).isEqualTo(faceLoc);
    }

    @Test
    void emptyHuGoesFarthestFromExitAtLowPriority() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();

        when(masterData.block(eq(block)))
                .thenReturn(new MasterDataClient.Block(block, "SHUTTLE_ASRS", "BLOCK", true, null));
        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(loc(near, "A1", 3, 1.0), loc(far, "A1", 3, 10.0)));

        // Empty carrier: no SKU, explicit block.
        PutawayDecision decision = service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), null, null, null, null, block, "TOTE", true, null));

        assertThat(decision.locationId()).isEqualTo(far);          // farthest from exit
        assertThat(decision.transportPriority()).isEqualTo("LOW");
        assertThat(decision.assignmentId()).isNotNull();
    }

    @Test
    void profileLessSkuFallsBackToTheOnlyAutomatedBlock() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID(); // no storage profile saved for this SKU
        UUID automatedBlock = UUID.randomUUID();
        UUID manualBlock = UUID.randomUUID();
        UUID slot = UUID.randomUUID();

        // The warehouse has exactly ONE automated block (the manual one doesn't count).
        when(masterData.blocks(eq(wh))).thenReturn(List.of(
                new MasterDataClient.Block(automatedBlock, "SHUTTLE_ASRS", "BLOCK", true, null),
                new MasterDataClient.Block(manualBlock, "MANUAL_RACK", "BLOCK", false, null)));
        when(masterData.storageLocations(eq(wh), eq(automatedBlock)))
                .thenReturn(List.of(loc(slot, "A1", 3, 1.0)));

        PutawayDecision decision = service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, null, BigDecimal.ONE, null, null, false, null));

        assertThat(decision.blockId()).isEqualTo(automatedBlock);
        assertThat(decision.locationId()).isEqualTo(slot);
    }

    @Test
    void profileLessSkuWithSeveralAutomatedBlocksStillRejects() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID(); // no storage profile saved for this SKU

        // Two automated candidates: ambiguous without a profile -> the existing 400 stays.
        when(masterData.blocks(eq(wh))).thenReturn(List.of(
                new MasterDataClient.Block(UUID.randomUUID(), "SHUTTLE_ASRS", "BLOCK", true, null),
                new MasterDataClient.Block(UUID.randomUUID(), "AUTOSTORE", "BLOCK", true, null)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, null, BigDecimal.ONE, null, null, false, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no storage profile / block");
    }

    @Test
    void rejectsHuTypeNotAllowedInTheBlock() {
        UUID wh = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        UUID block = UUID.randomUUID();

        StorageProfile profile = new StorageProfile();
        profile.setWarehouseId(wh);
        profile.setSkuId(sku);
        profile.setBlockId(block);
        profiles.save(profile);

        // Block only stores TOTEs; a PALLET must be rejected.
        when(masterData.block(eq(block)))
                .thenReturn(new MasterDataClient.Block(block, "SHUTTLE_ASRS", "BLOCK", true, List.of("TOTE")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.assign(
                new PutawayRequest(wh, UUID.randomUUID(), sku, null, null, BigDecimal.ONE, block, "PALLET", false, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiCompartmentHuPlacedByDominantCompartmentVelocity() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        UUID far = UUID.randomUUID();
        UUID skuA = UUID.randomUUID(); // dominant (qty 10)
        UUID skuB = UUID.randomUUID(); // qty 2

        StorageProfile dominant = new StorageProfile();
        dominant.setWarehouseId(wh);
        dominant.setSkuId(skuA);
        dominant.setBlockId(block);
        dominant.setVelocityClass("A"); // dominant is a fast mover → near the exit
        profiles.save(dominant);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(loc(far, "A1", 3, 10.0), loc(near, "A1", 3, 1.0)));

        PutawayRequest req = new PutawayRequest(wh, UUID.randomUUID(), null, null, null, null, block, "TOTE", false,
                List.of(new PutawayRequest.Compartment(skuA, new BigDecimal("10")),
                        new PutawayRequest.Compartment(skuB, new BigDecimal("2"))));

        PutawayDecision decision = service.assign(req);

        assertThat(decision.mode()).isEqualTo("RESERVE");
        assertThat(decision.locationId()).isEqualTo(near); // placed by dominant skuA (class A)
    }
}
