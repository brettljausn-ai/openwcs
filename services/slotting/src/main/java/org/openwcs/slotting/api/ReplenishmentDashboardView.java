package org.openwcs.slotting.api;

import java.util.List;

/**
 * Read-only replenishment health snapshot for one warehouse (Dashboards &amp; alerting epic).
 *
 * @param urgent             count of EMERGENCY-class open replenishment tasks (pick faces empty /
 *                           blocking demand)
 * @param openTasks          count of all open (PLANNED) replenishment tasks
 * @param oldestAgeMin       age in minutes of the oldest open task (0 when none)
 * @param projectedStockouts best-effort per-face stockout projections, soonest first
 */
public record ReplenishmentDashboardView(
        long urgent,
        long openTasks,
        long oldestAgeMin,
        List<ProjectedStockout> projectedStockouts) {

    /**
     * A pick face projected to run dry. {@code locationCode} is the face's location id rendered as
     * text (the slotting service holds location ids, not codes; master-data owns the human code).
     */
    public record ProjectedStockout(String locationCode, long minutesToStockout) {
    }
}
