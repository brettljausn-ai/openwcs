package org.openwcs.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * Read access to the warehouse KPIs and thresholds the evaluator compares (Dashboards &amp; alerting
 * epic). These contracts are owned by the sibling services (master-data / flow / inventory /
 * allocation / slotting / order-management), built in parallel; the DTOs here are this service's own
 * view of them and only carry the fields the evaluator needs. Every call is best-effort: a source
 * being down must not crash the evaluation loop (the HTTP impl returns null / empty on failure).
 */
public interface MetricsClient {

    /** Warehouse IDs to scan, discovered from master-data when no fixed list is configured. */
    List<UUID> warehouseIds();

    /** The configured alert thresholds (master-data); null if unavailable. */
    AlertThresholds thresholds();

    /** flow GET /api/flow/reports/automation-summary?warehouseId; null if unavailable. */
    AutomationSummary automationSummary(UUID warehouseId);

    /** inventory GET /api/inventory/reports/dashboard?warehouseId; null if unavailable. */
    InventoryDashboard inventoryDashboard(UUID warehouseId);

    /** allocation GET /api/allocation/reports/stock-blocking?warehouseId; null if unavailable. */
    StockBlocking stockBlocking(UUID warehouseId);

    /** slotting GET /api/slotting/replenishment/dashboard?warehouseId; null if unavailable. */
    ReplenishmentDashboard replenishmentDashboard(UUID warehouseId);

    /** order-management GET /api/orders/reports/dashboard?warehouseId; null if unavailable. */
    OrdersDashboard ordersDashboard(UUID warehouseId);

    // --- DTOs (only the fields the evaluator uses; unknown JSON properties ignored) ---

    /** master-data alert thresholds. Any field may be null (= that metric is not alerted on). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AlertThresholds(
            Double scanNoReadPct,
            Double receiveErrorsDay,
            Double asrsUtilisationPct,
            Double blockedLinesPct,
            Double putawayBacklogAgeMin,
            Double replenishmentOldestAgeMin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AutomationSummary(Double scanNoReadPctToday, Double equipmentAvailabilityPct) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InventoryDashboard(Double asrsUtilisationPct, PutawayBacklog putawayBacklog) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record PutawayBacklog(Long count, Double oldestAgeMin) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StockBlocking(Long blockedLines, Long openOutboundLines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReplenishmentDashboard(Long urgent, Double oldestAgeMin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrdersDashboard(Inbound inbound) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Inbound(Long husErrorsToday) {
        }
    }
}
