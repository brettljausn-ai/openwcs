package org.openwcs.orders.api;

/**
 * Result of the demo dashboard seed: how many orders, lines and stock-posting transactions were
 * backfilled to light up the order-flow / dashboard / dispatch / SLA reports.
 */
public record DemoDashboardSeedResult(int orders, int lines, int transactions) {
}
