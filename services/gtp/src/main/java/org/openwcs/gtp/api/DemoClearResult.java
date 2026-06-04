package org.openwcs.gtp.api;

/**
 * Counts of transactional goods-to-person rows removed by a demo full-reset clear
 * (build.md §4.8). Station/node configuration (GtpStation, StationNode) is untouched; only
 * the per-warehouse operational state — work cycles and their put instructions / task lines,
 * workplace sessions, and open destination demand — is purged.
 */
public record DemoClearResult(
        int workCycles,
        int putInstructions,
        int taskLines,
        int workplaceSessions,
        int destinationDemands) {}
