package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only ABC velocity / movers snapshot for one warehouse (Dashboards &amp; alerting epic).
 *
 * <p>The slotting velocity store persists only a decayed EWMA pick-frequency score per SKU (no raw
 * windowed pick counts), so {@code picks} throughout this view is that decayed score used as the
 * trailing pick-frequency proxy. {@code risers}/{@code fallers} approximate the 14d-vs-90d trend
 * from the un-folded recent picks against the established score — see
 * {@code VelocityDashboardService} for the exact derivation.
 *
 * @param a       number of A-class SKUs
 * @param b       number of B-class SKUs
 * @param c       number of C-class SKUs
 * @param pareto  every SKU ranked by descending pick-frequency proxy with a running cumulative %
 * @param top     up to 10 fastest movers
 * @param bottom  up to 10 slowest movers (with any observed velocity)
 * @param risers  up to 10 SKUs whose short-window rate most exceeds their long-window rate
 * @param fallers up to 10 SKUs whose short-window rate most trails their long-window rate
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

    /** A trend: a SKU's short-window vs long-window rate as percentages of total warehouse activity. */
    public record Trend(UUID skuId, BigDecimal pct14d, BigDecimal pct90d) {
    }
}
