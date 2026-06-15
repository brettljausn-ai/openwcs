package org.openwcs.notification.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.notification.client.MetricsClient;
import org.openwcs.notification.client.MetricsClient.AlertThresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls each warehouse's KPIs from the sibling services, compares them against the master-data
 * thresholds, and opens / clears alerts. The whole run is best-effort: a source being down yields a
 * null DTO (logged by the client) which simply means that metric is skipped this tick — never a
 * crash. Severity escalates to CRITICAL when a metric is far over (≥ 1.5×) its threshold.
 */
@Service
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    /** A value at or above this multiple of its threshold is CRITICAL rather than WARNING. */
    static final double CRITICAL_FACTOR = 1.5;

    private final MetricsClient metrics;
    private final AlertService alerts;

    public AlertEvaluator(MetricsClient metrics, AlertService alerts) {
        this.metrics = metrics;
        this.alerts = alerts;
    }

    /** One full pass over every configured / discovered warehouse. */
    public void evaluateAll() {
        AlertThresholds t = metrics.thresholds();
        if (t == null) {
            log.warn("alert thresholds unavailable from master-data — skipping this evaluation tick");
            return;
        }
        List<UUID> warehouses = metrics.warehouseIds();
        if (warehouses.isEmpty()) {
            log.debug("no warehouses to evaluate");
            return;
        }
        for (UUID warehouseId : warehouses) {
            try {
                evaluateWarehouse(warehouseId, t);
            } catch (Exception e) {
                // Defensive: a single warehouse must not abort the rest of the loop.
                log.warn("evaluation failed for warehouse {}: {}", warehouseId, e.toString());
            }
        }
    }

    void evaluateWarehouse(UUID warehouseId, AlertThresholds t) {
        // SCAN no-read rate (flow automation-summary vs scanNoReadPct).
        MetricsClient.AutomationSummary flow = metrics.automationSummary(warehouseId);
        if (flow != null && flow.scanNoReadPctToday() != null) {
            check(warehouseId, "SCAN", "scanNoReadPct", flow.scanNoReadPctToday(), t.scanNoReadPct());
        }

        // RECEIVING errors today (order-management dashboard vs receiveErrorsDay).
        MetricsClient.OrdersDashboard orders = metrics.ordersDashboard(warehouseId);
        if (orders != null && orders.inbound() != null && orders.inbound().husErrorsToday() != null) {
            check(warehouseId, "RECEIVING", "receiveErrorsDay",
                    orders.inbound().husErrorsToday().doubleValue(), t.receiveErrorsDay());
        }

        // ASRS utilisation (inventory dashboard vs asrsUtilisationPct).
        MetricsClient.InventoryDashboard inv = metrics.inventoryDashboard(warehouseId);
        if (inv != null) {
            if (inv.asrsUtilisationPct() != null) {
                check(warehouseId, "ASRS", "asrsUtilisationPct", inv.asrsUtilisationPct(), t.asrsUtilisationPct());
            }
            // Put-away backlog age (inventory dashboard vs putawayBacklogAgeMin).
            if (inv.putawayBacklog() != null && inv.putawayBacklog().oldestAgeMin() != null) {
                check(warehouseId, "PUTAWAY", "putawayBacklogAgeMin",
                        inv.putawayBacklog().oldestAgeMin(), t.putawayBacklogAgeMin());
            }
        }

        // Blocked outbound lines as a percentage (allocation stock-blocking vs blockedLinesPct).
        MetricsClient.StockBlocking blocking = metrics.stockBlocking(warehouseId);
        if (blocking != null && blocking.blockedLines() != null && blocking.openOutboundLines() != null
                && blocking.openOutboundLines() > 0) {
            double pct = 100.0 * blocking.blockedLines() / blocking.openOutboundLines();
            check(warehouseId, "ALLOCATION", "blockedLinesPct", pct, t.blockedLinesPct());
        }

        // Oldest urgent replenishment age (slotting replenishment dashboard vs replenishmentOldestAgeMin).
        MetricsClient.ReplenishmentDashboard repl = metrics.replenishmentDashboard(warehouseId);
        if (repl != null && repl.oldestAgeMin() != null) {
            check(warehouseId, "REPLENISHMENT", "replenishmentOldestAgeMin",
                    repl.oldestAgeMin(), t.replenishmentOldestAgeMin());
        }
    }

    /**
     * Compare one observed value against its threshold (a higher value is worse for all current
     * metrics): over threshold → raise (WARNING, or CRITICAL when ≥ {@link #CRITICAL_FACTOR}×);
     * at-or-under → clear. A null threshold means the metric is not alerted on (skip).
     */
    private void check(UUID warehouseId, String area, String metric, double value, Double threshold) {
        if (threshold == null) {
            return;
        }
        if (value > threshold) {
            String severity = value >= threshold * CRITICAL_FACTOR ? "CRITICAL" : "WARNING";
            alerts.raise(warehouseId, area, metric, severity,
                    BigDecimal.valueOf(value), BigDecimal.valueOf(threshold));
        } else {
            alerts.clear(warehouseId, area, metric);
        }
    }
}
