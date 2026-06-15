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
 */
public record InventoryDashboard(
        long husReceivedToday,
        long huCount,
        long skuCountWithStock,
        Double utilisationPct,
        Double asrsUtilisationPct,
        PutawayBacklog putawayBacklog) {

    /** Put-away backlog: how many HUs sit at receiving / UNKNOWN, and the oldest one's age. */
    public record PutawayBacklog(long count, Long oldestAgeMin) {
    }
}
