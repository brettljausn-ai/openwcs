package org.openwcs.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openwcs.notification.api.AlertHealthView;
import org.openwcs.notification.api.DemoClearResult;
import org.openwcs.notification.api.DemoDashboardSeedResult;
import org.openwcs.notification.client.DemoModeClient;
import org.openwcs.notification.client.MetricsClient;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.repo.AlertEventRepository;
import org.openwcs.notification.service.AlertService;
import org.openwcs.notification.service.DemoDashboardSeedService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The notification demo dashboard seed backfills active + historical alerts (incl. a chattering key
 * and a stale OPEN row) when demo mode is ON, lighting the andon board and alert-system-health; it
 * is a 409 (IllegalStateException) when demo mode is OFF.
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
    DemoModeClient demoMode;

    @MockBean
    MetricsClient metrics;

    @Autowired
    DemoDashboardSeedService seed;

    @Autowired
    AlertService alerts;

    @Autowired
    AlertEventRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    @Test
    void seedsAndonAndHealthWhenDemoModeOn() {
        when(demoMode.demoEnabled()).thenReturn(true);
        UUID warehouse = UUID.randomUUID();

        DemoDashboardSeedResult result = seed.seed(warehouse);
        assertThat(result.alertsCreated()).isGreaterThan(5);

        // Andon: at least the 2 active OPEN alerts + the stale OPEN (all active).
        var active = alerts.activeForWarehouse(warehouse);
        assertThat(active).isNotEmpty();
        assertThat(active).anyMatch(a -> "WARNING".equals(a.getSeverity()));
        assertThat(active).anyMatch(a -> "CRITICAL".equals(a.getSeverity()));

        // Health: chattering key present, a stale OPEN present, per-day chart over 14 days.
        AlertHealthView health = alerts.health(warehouse, 14, 1440);
        assertThat(health.chattering()).isNotEmpty();
        assertThat(health.stale()).isNotEmpty();
        assertThat(health.perDay()).hasSize(14);
        assertThat(health.activeBySeverity().warning()).isGreaterThanOrEqualTo(1);
        assertThat(health.activeBySeverity().critical()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void rejectsWhenDemoModeOff() {
        when(demoMode.demoEnabled()).thenReturn(false);
        assertThatThrownBy(() -> seed.seed(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repo.findAll()).isEmpty();
    }

    @Test
    void clearRemovesDemoAlertsButKeepsRealOnes() {
        when(demoMode.demoEnabled()).thenReturn(true);
        UUID warehouse = UUID.randomUUID();

        DemoDashboardSeedResult seeded = seed.seed(warehouse);
        assertThat(seeded.alertsCreated()).isGreaterThan(5);

        // A real (non-demo) alert in the same warehouse — its dedupe key carries no DEMO marker.
        AlertEvent real = new AlertEvent();
        real.setWarehouseId(warehouse);
        real.setArea("SCAN");
        real.setMetric("scanNoReadPct");
        real.setSeverity("WARNING");
        real.setState("OPEN");
        real.setDedupeKey(warehouse + "|SCAN|scanNoReadPct");
        repo.save(real);

        long before = repo.findAll().size();
        DemoClearResult result = seed.clear(warehouse);
        assertThat(result.alertsRemoved()).isEqualTo((int) (before - 1));

        // Only the real alert survives the demo clear.
        var remaining = repo.findAll();
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getDedupeKey()).doesNotContain("|DEMO-");
    }
}
