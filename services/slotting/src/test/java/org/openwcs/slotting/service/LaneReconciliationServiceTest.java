package org.openwcs.slotting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.api.LaneReconciliationReport;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.client.MasterDataClient.Block;
import org.openwcs.slotting.client.MasterDataClient.StorageLocation;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.openwcs.slotting.repo.PutawayAssignmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Lane-depth ledger reconciliation over PostgreSQL with master-data + inventory mocked. */
@SpringBootTest
@Testcontainers
class LaneReconciliationServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    LaneReconciliationService service;
    @Autowired
    PutawayAssignmentRepository assignments;

    @MockBean
    MasterDataClient masterData;
    @MockBean
    InventoryClient inventory;

    /** A 3-deep lane cell: aisle A1, level 1, column 5, depth {@code posZ}. */
    private static StorageLocation cell(UUID id, int posZ) {
        return new StorageLocation(id, "A1-1-5-" + posZ, "STORAGE", "ASRS_SLOT", "A1", 1, 3,
                5, 1, posZ, null, null, "ACTIVE", null);
    }

    private PutawayAssignment seedAssignment(UUID wh, UUID block, UUID cellId) {
        PutawayAssignment a = new PutawayAssignment();
        a.setWarehouseId(wh);
        a.setSkuId(UUID.randomUUID());
        a.setBlockId(block);
        a.setChosenLocationId(cellId);
        a.setHuId(UUID.randomUUID());
        a.setMode("RESERVE");
        a.setStatus("PLANNED");
        return assignments.save(a);
    }

    @Test
    void reportsDriftAndCorrectsGhostWhenInventoryShowsLessDepth() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();

        // Slotting assumes 2 cells occupied (face + middle); inventory says only the face holds stock.
        PutawayAssignment face = seedAssignment(wh, block, c1);
        PutawayAssignment ghost = seedAssignment(wh, block, c2);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(cell(c1, 1), cell(c2, 2), cell(c3, 3)));
        when(inventory.occupiedLocations(eq(wh), any())).thenReturn(Set.of(c1));

        LaneReconciliationReport report = service.reconcileBlock(wh, block);

        assertThat(report.lanesChecked()).isEqualTo(1);
        assertThat(report.corrected()).isEqualTo(1);
        assertThat(report.discrepancies()).hasSize(1);
        LaneReconciliationReport.Discrepancy d = report.discrepancies().get(0);
        assertThat(d.blockId()).isEqualTo(block);
        assertThat(d.expectedDepth()).isEqualTo(2);
        assertThat(d.actualDepth()).isEqualTo(1);
        assertThat(d.locationId()).isEqualTo(c1); // the aisle-face cell represents the lane

        // The ghost assignment was corrected to truth; the real one is untouched.
        assertThat(assignments.findById(ghost.getId()).orElseThrow().getStatus()).isEqualTo("RECONCILED");
        assertThat(assignments.findById(face.getId()).orElseThrow().getStatus()).isEqualTo("PLANNED");
    }

    @Test
    void noDiscrepancyWhenLedgerMatchesInventory() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();

        seedAssignment(wh, block, c1);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(cell(c1, 1), cell(c2, 2)));
        when(inventory.occupiedLocations(eq(wh), any())).thenReturn(Set.of(c1));

        LaneReconciliationReport report = service.reconcileBlock(wh, block);

        assertThat(report.lanesChecked()).isEqualTo(1);
        assertThat(report.corrected()).isZero();
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    void inventoryDownDegradesToEmptyReportAndCorrectsNothing() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();

        PutawayAssignment a = seedAssignment(wh, block, c1);

        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(cell(c1, 1), cell(c2, 2)));
        when(inventory.occupiedLocations(eq(wh), any()))
                .thenThrow(new RuntimeException("inventory unavailable"));

        LaneReconciliationReport report = service.reconcileBlock(wh, block);

        assertThat(report.lanesChecked()).isZero();
        assertThat(report.corrected()).isZero();
        assertThat(report.discrepancies()).isEmpty();
        // Best-effort: nothing is corrected when the truth source is down.
        assertThat(assignments.findById(a.getId()).orElseThrow().getStatus()).isEqualTo("PLANNED");
    }

    @Test
    void singleDeepLanesAreNotReconciled() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();

        // laneDepth 1: not a multi-deep lane, so it is excluded from depth reconciliation.
        StorageLocation single = new StorageLocation(c1, "B1", "STORAGE", "RACK", "B1", 1, 1,
                1, 1, 1, null, null, "ACTIVE", null);
        seedAssignment(wh, block, c1);
        when(masterData.storageLocations(eq(wh), eq(block))).thenReturn(List.of(single));

        LaneReconciliationReport report = service.reconcileBlock(wh, block);

        assertThat(report.lanesChecked()).isZero();
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    void reconcileWarehouseFansOutOverBlocks() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();

        seedAssignment(wh, block, c1);
        seedAssignment(wh, block, c2);

        when(masterData.blocks(eq(wh))).thenReturn(List.of(new Block(block, "ASRS", "CELL", false, null)));
        when(masterData.storageLocations(eq(wh), eq(block)))
                .thenReturn(List.of(cell(c1, 1), cell(c2, 2)));
        when(inventory.occupiedLocations(eq(wh), any())).thenReturn(Set.of(c1));

        LaneReconciliationReport report = service.reconcile(wh);

        assertThat(report.lanesChecked()).isEqualTo(1);
        assertThat(report.corrected()).isEqualTo(1);
        assertThat(report.discrepancies()).hasSize(1);
        assertThat(report.discrepancies().get(0).expectedDepth()).isEqualTo(2);
        assertThat(report.discrepancies().get(0).actualDepth()).isEqualTo(1);
    }
}
