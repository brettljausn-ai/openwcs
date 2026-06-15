package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.InventoryDashboard;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataClient.StorageBlockRef;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.InventoryReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Inventory dashboard KPIs against a real PostgreSQL 16: HU counts come from the HU table,
 * SKU-with-stock from the stock table, utilisation reuses the storage-density occupied/total
 * computation (overall + ASRS-typed blocks), and the put-away backlog counts HUs at the
 * RECEIVING / UNKNOWN locations. Best-effort: utilisation and backlog age degrade to null.
 */
@SpringBootTest
@Testcontainers
class InventoryDashboardReportTest {

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
    InventoryReportService reports;

    @Autowired
    StockRepository stock;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @MockBean
    MasterDataClient masterData;

    private void seedStockAt(UUID wh, UUID sku, UUID location) {
        Stock row = new Stock();
        row.setWarehouseId(wh);
        row.setSkuId(sku);
        row.setLocationId(location);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal("3"));
        stock.save(row);
    }

    private HandlingUnit seedHuAt(UUID wh, UUID location) {
        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(wh);
        hu.setCode("HU-" + UUID.randomUUID());
        hu.setLocationId(location);
        hu.setStatus("ACTIVE");
        return handlingUnits.save(hu);
    }

    @Test
    void aggregatesCountsUtilisationAndBacklog() {
        UUID wh = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        UUID receiving = UUID.randomUUID();
        UUID asrsCell0 = UUID.randomUUID();
        UUID asrsCell1 = UUID.randomUUID();
        UUID manualCell0 = UUID.randomUUID();
        UUID manualCell1 = UUID.randomUUID();
        UUID asrsBlock = UUID.randomUUID();
        UUID manualBlock = UUID.randomUUID();

        when(masterData.unknownLocationId(wh)).thenReturn(unknown);
        when(masterData.receivingLocationIds(wh)).thenReturn(List.of(receiving));
        when(masterData.storageBlocks(wh)).thenReturn(List.of(
                new StorageBlockRef(asrsBlock, "SHUTTLE_ASRS"),
                new StorageBlockRef(manualBlock, "MANUAL_PICK")));
        when(masterData.blockLocationIds(wh, asrsBlock)).thenReturn(List.of(asrsCell0, asrsCell1));
        when(masterData.blockLocationIds(wh, manualBlock)).thenReturn(List.of(manualCell0, manualCell1));

        // Two distinct SKUs with stock; one ASRS cell occupied, one manual cell occupied.
        UUID skuA = UUID.randomUUID();
        UUID skuB = UUID.randomUUID();
        seedStockAt(wh, skuA, asrsCell0);   // ASRS occupied 1/2
        seedStockAt(wh, skuB, manualCell0); // manual occupied 1/2

        // HUs: two at storage cells, one at receiving, one at UNKNOWN (the backlog = 2).
        seedHuAt(wh, asrsCell0);
        seedHuAt(wh, manualCell0);
        seedHuAt(wh, receiving);
        seedHuAt(wh, unknown);

        InventoryDashboard d = reports.dashboard(wh);

        assertThat(d.husReceivedToday()).isEqualTo(4);  // all four created just now
        assertThat(d.huCount()).isEqualTo(4);
        assertThat(d.skuCountWithStock()).isEqualTo(2);
        // Overall: 2 occupied of 4 cells = 50%; ASRS: 1 of 2 = 50%.
        assertThat(d.utilisationPct()).isEqualTo(50.0);
        assertThat(d.asrsUtilisationPct()).isEqualTo(50.0);
        // Backlog: HU at receiving + HU at UNKNOWN = 2; age derivable (>= 0 min).
        assertThat(d.putawayBacklog().count()).isEqualTo(2);
        assertThat(d.putawayBacklog().oldestAgeMin()).isNotNull();
        assertThat(d.putawayBacklog().oldestAgeMin()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void utilisationIsNullWhenMasterDataIsDownAndBacklogDegradesToZero() {
        UUID wh = UUID.randomUUID();
        when(masterData.storageBlocks(wh))
                .thenThrow(new MasterDataUnavailableException("down", null));
        when(masterData.receivingLocationIds(wh))
                .thenThrow(new MasterDataUnavailableException("down", null));

        UUID sku = UUID.randomUUID();
        seedStockAt(wh, sku, UUID.randomUUID());
        seedHuAt(wh, UUID.randomUUID());

        InventoryDashboard d = reports.dashboard(wh);

        // Local counts still answer.
        assertThat(d.huCount()).isEqualTo(1);
        assertThat(d.skuCountWithStock()).isEqualTo(1);
        // Utilisation + backlog degrade to null / zero rather than 500-ing.
        assertThat(d.utilisationPct()).isNull();
        assertThat(d.asrsUtilisationPct()).isNull();
        assertThat(d.putawayBacklog().count()).isZero();
        assertThat(d.putawayBacklog().oldestAgeMin()).isNull();
    }

    @Test
    void asrsUtilisationIsNullWithNoAsrsBlocks() {
        UUID wh = UUID.randomUUID();
        UUID block = UUID.randomUUID();
        UUID cell = UUID.randomUUID();
        when(masterData.unknownLocationId(wh)).thenReturn(UUID.randomUUID());
        when(masterData.receivingLocationIds(wh)).thenReturn(List.of());
        when(masterData.storageBlocks(wh)).thenReturn(List.of(
                new StorageBlockRef(block, "MANUAL_PICK")));
        when(masterData.blockLocationIds(wh, block)).thenReturn(List.of(cell));

        seedStockAt(wh, UUID.randomUUID(), cell);

        InventoryDashboard d = reports.dashboard(wh);

        assertThat(d.utilisationPct()).isEqualTo(100.0); // 1 of 1 manual cell
        assertThat(d.asrsUtilisationPct()).isNull();     // no ASRS cells to measure
        assertThat(d.putawayBacklog().count()).isZero();
    }
}
