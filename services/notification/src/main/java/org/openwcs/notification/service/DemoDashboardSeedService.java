package org.openwcs.notification.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openwcs.notification.api.DemoDashboardSeedResult;
import org.openwcs.notification.client.DemoModeClient;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.repo.AlertEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo DASHBOARD seeding (build.md §4.8) for notification. Backfills a handful of {@code alert_event}
 * rows so the andon board (/api/notification/alerts) and alert-system-health (/alerts/health) show
 * plausible numbers on a demo box:
 *
 * <ul>
 *   <li>two active alerts (1 WARNING + 1 CRITICAL, OPEN);</li>
 *   <li>some historical opened/cleared rows across the last 14 days (the per-day chart);</li>
 *   <li>one "chattering" key (several clears on the same area/metric);</li>
 *   <li>one "stale" OPEN row older than a day (the staleness list).</li>
 * </ul>
 *
 * <p>Gated to demo mode (no-op-with-error / 409 when off); strictly demo-only. Active rows each get
 * a distinct {@code dedupe_key} (the partial unique index forbids two active rows per key); cleared
 * rows may reuse a key.
 */
@Service
public class DemoDashboardSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoDashboardSeedService.class);

    private final AlertEventRepository repo;
    private final DemoModeClient demoMode;

    public DemoDashboardSeedService(AlertEventRepository repo, DemoModeClient demoMode) {
        this.repo = repo;
        this.demoMode = demoMode;
    }

    /**
     * Backfill andon + alert-health data for a warehouse.
     *
     * @throws IllegalStateException if demo mode is not enabled
     */
    @Transactional
    public DemoDashboardSeedResult seed(UUID warehouseId) {
        if (!demoMode.demoEnabled()) {
            throw new IllegalStateException("Demo mode is not enabled (notification dashboard seed is demo-only).");
        }
        Instant now = Instant.now();
        List<AlertEvent> rows = new ArrayList<>();

        // Active alerts (andon board + active-by-severity).
        rows.add(row(warehouseId, "SCAN", "scanNoReadPct", "WARNING", "OPEN",
                now.minus(35, ChronoUnit.MINUTES), null, "6.2", "5.0"));
        rows.add(row(warehouseId, "ASRS", "asrsUtilisationPct", "CRITICAL", "OPEN",
                now.minus(15, ChronoUnit.MINUTES), null, "96.0", "90.0"));

        // Stale OPEN alert (opened ~2 days ago, well beyond the 24h staleness threshold).
        rows.add(row(warehouseId, "RECEIVING", "receiveErrorsDay", "CRITICAL", "OPEN",
                now.minus(2, ChronoUnit.DAYS), null, "12", "5"));

        // Chattering key: several opened-then-cleared cycles on one (area, metric) across the window.
        for (int d = 1; d <= 4; d++) {
            Instant opened = now.minus(d, ChronoUnit.DAYS);
            rows.add(row(warehouseId, "ALLOCATION", "stockBlockingPct", "WARNING", "CLEARED",
                    opened, opened.plus(40, ChronoUnit.MINUTES), "7.5", "5.0"));
        }

        // A spread of single historical opened/cleared rows over the last 14 days (per-day chart),
        // each a distinct area/metric so none of them counts as chattering.
        String[][] hist = {
            {"PUTAWAY", "putawayBacklog"}, {"REPLENISHMENT", "replenUrgent"},
            {"OUTBOUND", "dispatchLatePct"}, {"INBOUND", "dockToStockMin"},
            {"SCAN", "scanUnknownPct"}, {"ASRS", "asrsThroughput"},
        };
        for (int i = 0; i < hist.length; i++) {
            Instant opened = now.minus(2L + i * 2L, ChronoUnit.DAYS);
            rows.add(row(warehouseId, hist[i][0], hist[i][1], i % 2 == 0 ? "WARNING" : "CRITICAL",
                    "CLEARED", opened, opened.plus(90, ChronoUnit.MINUTES), "6.0", "5.0"));
        }

        repo.saveAll(rows);
        log.info("demo dashboard seed for warehouse {}: backfilled {} alert_event rows"
                        + " (2 active, 1 stale, 1 chattering key, {} historical) because an admin"
                        + " requested notification dashboard demo data",
                warehouseId, rows.size(), hist.length);
        return new DemoDashboardSeedResult(rows.size());
    }

    private static AlertEvent row(UUID warehouseId, String area, String metric, String severity,
            String state, Instant openedAt, Instant clearedAt, String value, String threshold) {
        AlertEvent a = new AlertEvent();
        a.setWarehouseId(warehouseId);
        a.setArea(area);
        a.setMetric(metric);
        a.setSeverity(severity);
        a.setState(state);
        a.setValue(new BigDecimal(value));
        a.setThreshold(new BigDecimal(threshold));
        a.setOpenedAt(openedAt);
        a.setClearedAt(clearedAt);
        // Cleared rows may share a key; active rows must be unique (partial unique index). A random
        // suffix on every key keeps inserts collision-free and clearly demo-only.
        a.setDedupeKey(warehouseId + "|" + area + "|" + metric + "|DEMO-"
                + UUID.randomUUID().toString().substring(0, 8));
        return a;
    }
}
