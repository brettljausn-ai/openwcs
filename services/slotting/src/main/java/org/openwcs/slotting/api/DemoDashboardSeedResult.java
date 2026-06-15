package org.openwcs.slotting.api;

/**
 * Result of the slotting demo dashboard seed: how many velocity rows, daily pick buckets and
 * replenishment tasks were backfilled to light up /velocity/abc and /replenishment/dashboard.
 */
public record DemoDashboardSeedResult(int velocityRows, int pickDays, int replenishmentTasks) {
}
