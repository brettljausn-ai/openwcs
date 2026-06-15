package org.openwcs.slotting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.slotting.api.DemoDashboardSeedResult;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.SkuPickDailyRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.service.DemoDashboardSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The slotting demo dashboard seed backfills velocity, daily picks and replenishment tasks when
 * demo mode is ON, and is a 409 (IllegalStateException) when demo mode is OFF.
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

    @MockBean
    MasterDataClient masterData;

    @MockBean
    InventoryClient inventory;

    @Autowired
    DemoDashboardSeedService seed;

    @Autowired
    SkuVelocityRepository velocity;

    @Autowired
    SkuPickDailyRepository dailyPicks;

    @Autowired
    ReplenishmentTaskRepository tasks;

    @Test
    void seedsVelocityPicksAndTasksWhenDemoModeOn() {
        when(masterData.demoEnabled()).thenReturn(true);
        UUID warehouse = UUID.randomUUID();

        DemoDashboardSeedResult result = seed.seed(warehouse);

        assertThat(result.velocityRows()).isEqualTo(18);
        assertThat(result.pickDays()).isGreaterThan(0);
        assertThat(result.replenishmentTasks()).isEqualTo(6);

        var rows = velocity.findByWarehouseId(warehouse);
        assertThat(rows).hasSize(18);
        assertThat(rows).anyMatch(r -> "A".equals(r.getVelocityClass()));
        assertThat(rows).anyMatch(r -> "C".equals(r.getVelocityClass()));

        // Some picks land inside the last-14-day window (risers) and some only earlier (fallers).
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(dailyPicks.windowTotal(warehouse, today.minusDays(13), today)).isGreaterThan(0);
        assertThat(dailyPicks.windowTotal(warehouse, today.minusDays(89), today.minusDays(14)))
                .isGreaterThan(0);

        assertThat(tasks.findByWarehouseIdAndStatus(warehouse, "PLANNED"))
                .anyMatch(t -> "EMERGENCY".equals(t.getPriority()));
    }

    @Test
    void rejectsWhenDemoModeOff() {
        when(masterData.demoEnabled()).thenReturn(false);
        assertThatThrownBy(() -> seed.seed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }
}
