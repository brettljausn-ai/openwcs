package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.InventoryController;
import org.openwcs.inventory.api.OccupiedLocationsRequest;
import org.openwcs.inventory.api.OccupiedLocationsResult;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Per-location occupancy endpoint against a real PostgreSQL 16: a location with a stock row and a
 * location with a handling unit both report occupied, while an empty location does not.
 */
@SpringBootTest
@Testcontainers
class OccupiedLocationsTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    InventoryController controller;

    @Autowired
    StockRepository stock;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @Test
    void returnsOnlyLocationsHoldingStockOrAHandlingUnit() {
        UUID warehouseId = UUID.randomUUID();
        UUID stockLoc = UUID.randomUUID();
        UUID huLoc = UUID.randomUUID();
        UUID emptyLoc = UUID.randomUUID();

        Stock row = new Stock();
        row.setWarehouseId(warehouseId);
        row.setSkuId(UUID.randomUUID());
        row.setLocationId(stockLoc);
        row.setStatus("AVAILABLE");
        row.setQty(new BigDecimal("3.0000"));
        stock.save(row);

        HandlingUnit hu = new HandlingUnit();
        hu.setWarehouseId(warehouseId);
        hu.setCode("HU-OCC-1");
        hu.setLocationId(huLoc);
        handlingUnits.save(hu);

        OccupiedLocationsResult result = controller.occupiedLocations(
                new OccupiedLocationsRequest(List.of(stockLoc, huLoc, emptyLoc)));

        assertThat(result.occupiedLocationIds())
                .containsExactlyInAnyOrder(stockLoc, huLoc)
                .doesNotContain(emptyLoc);
    }

    @Test
    void emptyInputReturnsEmptyList() {
        assertThat(controller.occupiedLocations(new OccupiedLocationsRequest(null)).occupiedLocationIds())
                .isEmpty();
        assertThat(controller.occupiedLocations(new OccupiedLocationsRequest(List.of())).occupiedLocationIds())
                .isEmpty();
    }
}
