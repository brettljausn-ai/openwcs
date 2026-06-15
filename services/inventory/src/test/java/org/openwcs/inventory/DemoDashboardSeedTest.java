package org.openwcs.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.inventory.api.DemoDashboardSeedRequest;
import org.openwcs.inventory.api.DemoDashboardSeedResult;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.service.DemoSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The inventory demo DASHBOARD seed reuses the occupancy seeder and adds dock-to-stock-timed HUs
 * (received + stored today) plus a couple of put-away-backlog HUs. It is a no-op (zeros) when no
 * SKUs/locations are supplied — i.e. when demo mode has not been enabled.
 */
@SpringBootTest
@Testcontainers
class DemoDashboardSeedTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    DemoSeedService demo;

    @Autowired
    HandlingUnitRepository handlingUnits;

    @Test
    void seedsDockToStockAndBacklogWhenDemoModeOn() {
        UUID warehouse = UUID.randomUUID();
        List<UUID> storage = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> receiving = List.of(UUID.randomUUID());
        List<UUID> skus = List.of(UUID.randomUUID(), UUID.randomUUID());

        DemoDashboardSeedResult result = demo.seedDashboard(
                new DemoDashboardSeedRequest(warehouse, UUID.randomUUID(), storage, receiving, skus));

        assertThat(result.dockToStockHus()).isEqualTo(12);
        assertThat(result.backlogHus()).isEqualTo(3);
        assertThat(result.stockRows()).isGreaterThan(0);

        Instant startOfToday = Instant.now().minusSeconds(86_400);
        List<HandlingUnit> stored = handlingUnits.findStoredSince(warehouse, startOfToday);
        assertThat(stored).hasSize(12);
        // Each stored HU has a received (created) time before its stored time, yielding positive
        // dock-to-stock minutes.
        assertThat(stored).allSatisfy(h -> {
            assertThat(h.getStoredAt()).isNotNull();
            assertThat(h.getCreatedAt()).isBefore(h.getStoredAt());
        });

        // Backlog HUs sit at the receiving location with no stored_at.
        List<HandlingUnit> backlog = handlingUnits.findBacklogByWarehouseIdAndLocationIdIn(warehouse, receiving);
        assertThat(backlog).hasSize(3).allSatisfy(h -> assertThat(h.getStoredAt()).isNull());
    }

    @Test
    void noOpWhenDemoModeOff() {
        UUID warehouse = UUID.randomUUID();
        DemoDashboardSeedResult result = demo.seedDashboard(
                new DemoDashboardSeedRequest(warehouse, null, List.of(), List.of(), List.of()));

        assertThat(result.dockToStockHus()).isZero();
        assertThat(result.backlogHus()).isZero();
        assertThat(result.stockRows()).isZero();
        assertThat(handlingUnits.findByWarehouseId(warehouse)).isEmpty();
    }
}
