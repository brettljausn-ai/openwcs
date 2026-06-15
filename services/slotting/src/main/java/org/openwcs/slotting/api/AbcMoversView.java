package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only ABC velocity / movers snapshot for one warehouse (Dashboards &amp; alerting epic).
 *
 * <p>{@code picks} throughout this view is the decayed EWMA pick-frequency score the classifier
 * ranks on (so the dashboard agrees with the assigned A/B/C labels). {@code risers}/{@code fallers}
 * carry the <em>exact</em> trailing-window shares summed from the daily pick tallies
 * ({@code sku_pick_daily}): {@code pct14d} is the SKU's last-14-day picks as a percent of the
 * warehouse 14-day total, {@code pct90d} the same over 90 days — see {@code VelocityDashboardService}.
 *
 * @param a       number of A-class SKUs
 * @param b       number of B-class SKUs
 * @param c       number of C-class SKUs
 * @param pareto  every SKU ranked by descending pick-frequency score with a running cumulative %
 * @param top     up to 10 fastest movers
 * @param bottom  up to 10 slowest movers (with any observed velocity)
 * @param risers  up to 10 SKUs whose trailing 14d pick share most exceeds their 90d share
 * @param fallers up to 10 SKUs whose trailing 14d pick share most trails their 90d share
 */
public record AbcMoversView(
        long a,
        long b,
        long c,
        List<ParetoEntry> pareto,
        List<Mover> top,
        List<Mover> bottom,
        List<Trend> risers,
        List<Trend> fallers) {

    /** One row of the Pareto curve: a SKU's pick-frequency proxy and the running cumulative share. */
    public record ParetoEntry(UUID skuId, BigDecimal picks, BigDecimal cumPct) {
    }

    /** A mover: a SKU and its pick-frequency proxy. */
    public record Mover(UUID skuId, BigDecimal picks) {
    }

    /** A trend: a SKU's trailing 14d vs 90d pick share as percentages of total warehouse activity. */
    public record Trend(UUID skuId, BigDecimal pct14d, BigDecimal pct90d) {
    }
}
