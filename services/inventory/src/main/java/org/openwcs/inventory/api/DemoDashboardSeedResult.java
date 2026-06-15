package org.openwcs.inventory.api;

/**
 * Result of the inventory demo dashboard seed: how many stocked / empty handling units and stock
 * rows were created (occupancy), plus how many dock-to-stock-timed and put-away-backlog HUs were
 * added to light up /reports/dashboard.
 */
public record DemoDashboardSeedResult(
        int handlingUnits,
        int emptyHandlingUnits,
        int stockRows,
        int dockToStockHus,
        int backlogHus) {
}
