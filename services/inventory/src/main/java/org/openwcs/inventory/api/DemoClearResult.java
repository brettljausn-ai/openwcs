package org.openwcs.inventory.api;

/**
 * Counts of transactional inventory rows removed by a demo full-reset clear
 * (build.md §4.8). Infrastructure / master-data references are untouched; only the
 * per-warehouse operational state (reservations, stock, handling units, serial units,
 * batches) is purged.
 */
public record DemoClearResult(
        int reservations, int stockRows, int handlingUnits, int serialUnits, int batches) {}
