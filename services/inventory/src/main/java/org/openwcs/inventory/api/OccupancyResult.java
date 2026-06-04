package org.openwcs.inventory.api;

/** Occupancy summary for a set of locations: how many stock rows and handling units they hold. */
public record OccupancyResult(long stockRows, long handlingUnits) {
}
