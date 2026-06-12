package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.StorageDensityRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.repo.StorageDensitySnapshotRepository;
import org.openwcs.inventory.service.StorageDensityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Storage-density snapshots against a real PostgreSQL 16: the sweep writes one row per block
 * per day (idempotent on rerun), occupancy counts distinct cells holding stock or HUs, and
 * the report endpoint snapshots today on demand when the sweep has not run yet.
 */
@SpringBootTest
@Testcontainers
class StorageDensityReportTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("openwcs.inventory.density-sweep.enabled", () -> "false");
    }

    @Autowired
    StorageDensityService density;

    @Autowired
    StorageDensitySnapshotRepository snapshots;

    @Autowired
    StockRepository stock;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @MockBean
    MasterDataClient masterData;

    private void seedStockAt(UUID wh, UUID location) {
        Stock row = new Stock();
        row.setWarehouseId(wh);
        row.setSkuId(UUID.randomUUID());
        row.setLocationId(location);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal("3"));
        stock.save(row);
    }

    private void seedHuAt(UUID wh, UUID location) {
        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(wh);
        hu.setCode("HU-" + UUID.randomUUID());
        hu.setLocationId(location);
        hu.setStatus("ACTIVE");
        handlingUnits.save(hu);
    }

    @Test
    void sweepWritesOneRowPerBlockPerDayAndRerunsAreIdempotent() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        List<UUID> cells = List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        when(masterData.storageBlockIds(wh)).thenReturn(List.of(block));
        when(masterData.blockLocationIds(wh, block)).thenReturn(cells);

        seedStockAt(wh, cells.get(0));
        seedHuAt(wh, cells.get(1));
        seedStockAt(wh, cells.get(1)); // same cell holding stock AND an HU counts once

        LocalDate today = StorageDensityService.today();
        density.snapshotWarehouse(wh, today);
        density.snapshotWarehouse(wh, today); // rerun must update, not duplicate

        var rows = snapshots.findByWarehouseIdAndDayGreaterThanEqualOrderByDayAscBlockIdAsc(
                wh, today.minusDays(1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBlockId()).isEqualTo(block);
        assertThat(rows.get(0).getOccupiedCells()).isEqualTo(2);
        assertThat(rows.get(0).getTotalCells()).isEqualTo(4);
    }

    @Test
    void historySnapshotsTodayOnDemandAndReturnsTheWindow() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        List<UUID> cells = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(masterData.storageBlockIds(wh)).thenReturn(List.of(block));
        when(masterData.blockLocationIds(wh, block)).thenReturn(cells);
        seedStockAt(wh, cells.get(0));

        // Yesterday's sweep result is already stored; today has no snapshot yet.
        LocalDate yesterday = StorageDensityService.today().minusDays(1);
        density.snapshotWarehouse(wh, yesterday);

        List<StorageDensityRow> history = density.history(wh, 90);

        assertThat(history).hasSize(2); // yesterday from the sweep + today on demand
        assertThat(history.get(0).day()).isEqualTo(yesterday);
        StorageDensityRow today = history.get(1);
        assertThat(today.day()).isEqualTo(StorageDensityService.today());
        assertThat(today.blockId()).isEqualTo(block);
        assertThat(today.occupiedCells()).isEqualTo(1);
        assertThat(today.totalCells()).isEqualTo(2);
        assertThat(today.pct()).isEqualTo(50.0);

        // Days outside the requested window are cut off.
        assertThat(density.history(wh, 1)).hasSize(1);
    }

    @Test
    void emptyBlockReportsZeroPctWithoutDivisionByZero() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        when(masterData.storageBlockIds(wh)).thenReturn(List.of(block));
        when(masterData.blockLocationIds(wh, block)).thenReturn(List.of());

        List<StorageDensityRow> history = density.history(wh, 90);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).totalCells()).isZero();
        assertThat(history.get(0).pct()).isZero();
    }
}
