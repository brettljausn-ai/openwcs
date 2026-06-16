package org.openwcs.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.openwcs.notification.client.MetricsClient;
import org.openwcs.notification.delivery.AlertEmailSender;
import org.openwcs.notification.delivery.AlertWebhookSender;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.repo.AlertEventRepository;
import org.openwcs.notification.service.AlertEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The evaluator over PostgreSQL with the metric sources mocked: a breach opens an alert, dedupes on
 * re-breach, clears when the metric recovers, and fires delivery only on the open / clear transitions.
 */
@SpringBootTest
@Testcontainers
class AlertEvaluatorTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    AlertEvaluator evaluator;

    @Autowired
    AlertEventRepository alerts;

    @MockBean
    MetricsClient metrics;

    // The two delivery channels are mocked so we can assert exactly when OPEN / CLEAR fire.
    @MockBean
    AlertEmailSender delivery;

    @MockBean
    AlertWebhookSender webhook;

    private final UUID warehouse = UUID.randomUUID();

    private void thresholds() {
        when(metrics.warehouseIds()).thenReturn(List.of(warehouse));
        when(metrics.thresholds()).thenReturn(new MetricsClient.AlertThresholds(
                5.0,    // scanNoReadPct
                10.0,   // receiveErrorsDay
                90.0,   // asrsUtilisationPct
                20.0,   // blockedLinesPct
                30.0,   // putawayBacklogAgeMin
                60.0)); // replenishmentOldestAgeMin
    }

    private void scanNoRead(double pct) {
        when(metrics.automationSummary(warehouse))
                .thenReturn(new MetricsClient.AutomationSummary(pct, 99.0));
    }

    /**
     * A delivery matcher scoped to THIS test's warehouse. The {@code delivery} mock is shared across
     * the class's test methods via the Spring context, so an unscoped {@code onOpen(any())} could
     * count a sibling method's invocation (each test uses its own random warehouse). Matching on the
     * warehouse keeps every verification deterministic regardless of execution order / isolation.
     */
    private AlertEvent ours() {
        return argThat(a -> a != null && warehouse.equals(a.getWarehouseId()));
    }

    @Test
    void breachOpensThenRecoveryClears() {
        thresholds();
        // 12% no-read against a 5% threshold → over, and ≥ 1.5× → CRITICAL.
        scanNoRead(12.0);
        evaluator.evaluateAll();

        List<AlertEvent> active = alerts.findByWarehouseIdAndStateInOrderByOpenedAtDesc(
                warehouse, List.of("OPEN", "ACKED"));
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getMetric()).isEqualTo("scanNoReadPct");
        assertThat(active.get(0).getSeverity()).isEqualTo("CRITICAL");
        verify(delivery, times(1)).onOpen(ours());

        // Metric recovers under threshold → clears + one clear delivery.
        scanNoRead(2.0);
        evaluator.evaluateAll();
        assertThat(alerts.findByWarehouseIdAndStateInOrderByOpenedAtDesc(
                warehouse, List.of("OPEN", "ACKED"))).isEmpty();
        verify(delivery, times(1)).onClear(ours());
    }

    @Test
    void sustainedBreachDoesNotDuplicate() {
        thresholds();
        scanNoRead(6.0); // over 5% but under 7.5% (1.5×) → WARNING
        evaluator.evaluateAll();
        evaluator.evaluateAll(); // second tick, still breached

        List<AlertEvent> active = alerts.findByWarehouseIdAndStateInOrderByOpenedAtDesc(
                warehouse, List.of("OPEN", "ACKED"));
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getSeverity()).isEqualTo("WARNING");
        // Delivery fires once (on the initial open), not on the dedup'd re-breach.
        verify(delivery, times(1)).onOpen(ours());
    }

    @Test
    void noThresholdsSkipsWithoutDelivery() {
        when(metrics.warehouseIds()).thenReturn(List.of(warehouse));
        when(metrics.thresholds()).thenReturn(null);
        evaluator.evaluateAll();
        verify(delivery, never()).onOpen(ours());
    }

    @Test
    void underThresholdOpensNothing() {
        thresholds();
        scanNoRead(1.0);
        evaluator.evaluateAll();
        assertThat(alerts.findByWarehouseIdAndStateInOrderByOpenedAtDesc(
                warehouse, List.of("OPEN", "ACKED"))).isEmpty();
        verify(delivery, never()).onOpen(ours());
        // A clear on a non-existent alert is a no-op (no delivery).
        verify(delivery, never()).onClear(ours());
    }

    @Test
    void deadSourceDoesNotCrashLoop() {
        thresholds();
        // automationSummary returns null (source down) — loop must complete cleanly.
        when(metrics.automationSummary(warehouse)).thenReturn(null);
        evaluator.evaluateAll();
        verify(metrics, atLeastOnce()).thresholds();
    }
}
