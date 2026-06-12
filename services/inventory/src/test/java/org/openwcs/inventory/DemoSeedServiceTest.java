package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.DemoSeedRequest;
import org.openwcs.inventory.api.DemoSeedResult;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.openwcs.inventory.service.DemoSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Demo seeding registers stocked handling units AND 50 empty ones (no stock rows) — the
 * empties feed the empty-HU flows (ASRS empty-HU management, GTP order totes). The clear
 * removes everything again.
 */
@SpringBootTest
@Testcontainers
class DemoSeedServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    DemoSeedService demo;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @Autowired
    StockRepository stock;

    @Test
    void seedCreatesStockedHusPlusFiftyEmptyOnes() {
        UUID warehouseId = UUID.randomUUID();
        List<UUID> locations = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> skus = List.of(UUID.randomUUID(), UUID.randomUUID());

        DemoSeedResult result = demo.seed(new DemoSeedRequest(warehouseId, UUID.randomUUID(), locations, skus));

        int stocked = Math.min(24, locations.size() * 2); // the existing stocked-HU formula
        assertThat(result.emptyHandlingUnits()).isEqualTo(50);
        assertThat(result.handlingUnits()).isEqualTo(stocked + 50);
        assertThat(result.stockRows()).isPositive();

        List<HandlingUnit> hus = handlingUnits.findByWarehouseId(warehouseId);
        assertThat(hus).hasSize(stocked + 50);
        // every seeded HU sits in one of the given locations; the empties carry no stock rows
        assertThat(hus).allSatisfy(hu -> assertThat(locations).contains(hu.getLocationId()));
        long husWithStock = stock.findByWarehouseId(warehouseId).stream()
                .map(s -> s.getHuId()).distinct().count();
        assertThat(husWithStock).isEqualTo(stocked);

        demo.clear(warehouseId);
        assertThat(handlingUnits.findByWarehouseId(warehouseId)).isEmpty();
        assertThat(stock.findByWarehouseId(warehouseId)).isEmpty();
    }
}
