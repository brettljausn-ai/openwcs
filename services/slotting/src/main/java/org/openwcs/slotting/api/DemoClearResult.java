package org.openwcs.slotting.api;

/**
 * Counts of slotting rows removed by a demo clear (build.md §4.8) when demo mode is turned off.
 * Only data the dashboard seeder backfills is removed: per-SKU velocity scores, daily pick buckets
 * (the windowed ABC movers) and the open (PLANNED) replenishment tasks. Block policies and other
 * configuration are untouched.
 */
public record DemoClearResult(int velocityRows, int pickDayRows, int replenishmentTasks) {}
