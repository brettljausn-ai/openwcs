package org.openwcs.inventory.api;

/**
 * Inventory dashboard KPIs for one warehouse (read-only, best-effort: percentages and the
 * backlog age degrade to null rather than failing the whole response when a dependency is down).
 *
 * @param husReceivedToday handling units created today
 * @param huCount total handling units currently in the warehouse
 * @param skuCountWithStock distinct SKUs that have any stock row
 * @param utilisationPct overall storage fill % (occupied/total cells), null if not derivable
 * @param asrsUtilisationPct ASRS-only storage fill %, null if there are no ASRS cells / md down
 * @param putawayBacklog HUs received but not yet stored away (count + oldest age in minutes)
 * @param dockToStock dock-to-stock put-away timing for HUs stored today (median minutes + samples)
 */
public record InventoryDashboard(
        long husReceivedToday,
        long huCount,
        long skuCountWithStock,
        Double utilisationPct,
        Double asrsUtilisationPct,
        PutawayBacklog putawayBacklog,
        DockToStock dockToStock) {

    /** Put-away backlog: how many HUs sit at receiving / UNKNOWN, and the oldest one's age. */
    public record PutawayBacklog(long count, Long oldestAgeMin) {
    }

    /**
     * Dock-to-stock timing: the time from an HU being received ({@code createdAt}) to it being
     * stored at a real storage slot ({@code storedAt}), for HUs stored today (UTC). The median is
     * in minutes; {@code samples} is how many HUs contributed. Both are null / 0 when no HU was
     * stored today (history accrues from the deployment that added stored_at).
     */
    public record DockToStock(Long medianMin, long samples) {
    }
}
