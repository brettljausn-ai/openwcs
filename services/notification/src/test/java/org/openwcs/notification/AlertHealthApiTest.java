package org.openwcs.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openwcs.notification.client.MetricsClient;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.repo.AlertEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The alert-system-health endpoint summarises the alert ledger (active-by-severity, per-day
 * opened/cleared, chattering keys, stale OPEN alerts). Mirrors {@link AlertApiTest}: Testcontainers
 * Postgres, seed rows directly, assert each section.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AlertHealthApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AlertEventRepository repo;

    // The evaluator's metric client is not exercised here, but is a required bean.
    @MockBean
    MetricsClient metrics;

    private UUID warehouse;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        warehouse = UUID.randomUUID();
    }

    private AlertEvent row(String area, String metric, String severity, String state,
                           Instant openedAt, Instant clearedAt) {
        AlertEvent a = new AlertEvent();
        a.setWarehouseId(warehouse);
        a.setArea(area);
        a.setMetric(metric);
        a.setSeverity(severity);
        a.setValue(new BigDecimal("6.0"));
        a.setThreshold(new BigDecimal("5.0"));
        a.setState(state);
        // dedupe_key NOT NULL; the active partial-unique index only constrains OPEN/ACKED rows, so
        // many CLEARED rows can share a key (that is exactly the chattering history).
        a.setDedupeKey(warehouse + "|" + area + "|" + metric);
        a.setOpenedAt(openedAt);
        a.setClearedAt(clearedAt);
        return a;
    }

    @Test
    void healthSummarisesActivePerDayChatteringAndStale() throws Exception {
        Instant now = Instant.now();

        // -- active by severity: 2 active WARNING (1 OPEN, 1 ACKED), 1 active CRITICAL --
        AlertEvent warnOpen = row("SCAN", "scanNoReadPct", "WARNING", "OPEN",
                now.minus(30, ChronoUnit.MINUTES), null);
        AlertEvent warnAcked = row("PUTAWAY", "putawayBacklog", "WARNING", "ACKED",
                now.minus(45, ChronoUnit.MINUTES), null);
        warnAcked.setAckedAt(now.minus(10, ChronoUnit.MINUTES));
        warnAcked.setAckedBy("supervisor1");
        AlertEvent critOpen = row("ASRS", "asrsUtilisationPct", "CRITICAL", "OPEN",
                now.minus(20, ChronoUnit.MINUTES), null);

        // -- a stale OPEN alert: opened ~2 days ago, beyond the 1440-min default staleness threshold --
        AlertEvent stale = row("RECEIVING", "receiveErrorsDay", "CRITICAL", "OPEN",
                now.minus(2, ChronoUnit.DAYS), null);

        // -- chattering key: 3 CLEARED rows over the window for one (area, metric) --
        AlertEvent flap1 = row("SCAN", "scanNoReadPct", "WARNING", "CLEARED",
                now.minus(3, ChronoUnit.DAYS), now.minus(3, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        AlertEvent flap2 = row("SCAN", "scanNoReadPct", "WARNING", "CLEARED",
                now.minus(2, ChronoUnit.DAYS), now.minus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        AlertEvent flap3 = row("SCAN", "scanNoReadPct", "WARNING", "CLEARED",
                now.minus(1, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));

        // -- a single (non-chattering) historical cleared row for another key --
        AlertEvent oneClear = row("ALLOCATION", "stockBlockingPct", "WARNING", "CLEARED",
                now.minus(4, ChronoUnit.DAYS), now.minus(4, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS));

        repo.saveAll(java.util.List.of(
                warnOpen, warnAcked, critOpen, stale, flap1, flap2, flap3, oneClear));

        mockMvc.perform(get("/api/notification/alerts/health")
                        .param("warehouseId", warehouse.toString())
                        .param("days", "14"))
                .andExpect(status().isOk())
                // active by severity (the stale CRITICAL OPEN counts here too -> 2 critical)
                .andExpect(jsonPath("$.activeBySeverity.warning").value(2))
                .andExpect(jsonPath("$.activeBySeverity.critical").value(2))
                // perDay zero-filled to 14 entries, oldest first
                .andExpect(jsonPath("$.perDay.length()").value(14))
                .andExpect(jsonPath("$.perDay[0].day").isNotEmpty())
                .andExpect(jsonPath("$.perDay[13].opened").exists())
                // chattering: SCAN/scanNoReadPct flapped 3 times, top of the list
                .andExpect(jsonPath("$.chattering[0].area").value("SCAN"))
                .andExpect(jsonPath("$.chattering[0].metric").value("scanNoReadPct"))
                .andExpect(jsonPath("$.chattering[0].flaps").value(3))
                // the single-clear ALLOCATION key is NOT chattering (needs >= 2)
                .andExpect(jsonPath("$.chattering.length()").value(1))
                // stale: the 2-day-old OPEN RECEIVING alert
                .andExpect(jsonPath("$.stale.length()").value(1))
                .andExpect(jsonPath("$.stale[0].id").value(stale.getId().toString()))
                .andExpect(jsonPath("$.stale[0].area").value("RECEIVING"))
                .andExpect(jsonPath("$.stale[0].metric").value("receiveErrorsDay"))
                .andExpect(jsonPath("$.stale[0].ageMin").value(org.hamcrest.Matchers.greaterThan(1440)));
    }

    @Test
    void perDayCountsOpenedAndClearedOnTheRightDays() throws Exception {
        Instant now = Instant.now();
        // Two opened today, one cleared today.
        repo.saveAll(java.util.List.of(
                row("SCAN", "scanNoReadPct", "WARNING", "OPEN", now.minus(1, ChronoUnit.HOURS), null),
                row("ASRS", "asrsUtilisationPct", "CRITICAL", "OPEN", now.minus(2, ChronoUnit.HOURS), null),
                row("PUTAWAY", "putawayBacklog", "WARNING", "CLEARED",
                        now.minus(5, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS))));

        mockMvc.perform(get("/api/notification/alerts/health")
                        .param("warehouseId", warehouse.toString())
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perDay.length()").value(7))
                // last entry is today (UTC): 3 opened (incl. the row that also cleared today), 1 cleared
                .andExpect(jsonPath("$.perDay[6].opened").value(3))
                .andExpect(jsonPath("$.perDay[6].cleared").value(1))
                // an earlier day is zero-filled
                .andExpect(jsonPath("$.perDay[0].opened").value(0))
                .andExpect(jsonPath("$.perDay[0].cleared").value(0));
    }

    @Test
    void warehouseFilterIsHonoured() throws Exception {
        Instant now = Instant.now();
        repo.save(row("SCAN", "scanNoReadPct", "WARNING", "OPEN", now.minus(1, ChronoUnit.HOURS), null));

        // A different warehouse sees nothing of ours.
        mockMvc.perform(get("/api/notification/alerts/health")
                        .param("warehouseId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBySeverity.warning").value(0))
                .andExpect(jsonPath("$.activeBySeverity.critical").value(0));

        // Omitting warehouseId aggregates across all warehouses (ours included).
        mockMvc.perform(get("/api/notification/alerts/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeBySeverity.warning").value(1));
    }
}
