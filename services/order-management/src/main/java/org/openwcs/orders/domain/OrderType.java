package org.openwcs.orders.domain;

/**
 * Warehouse order types. Each posts stock transactions beneath its lines:
 * INBOUND → receipts (+), OUTBOUND → picks (−), COUNT → count adjustments (signed),
 * ADJUSTMENT → manual add/remove (signed). Only OUTBOUND goes through allocation/release.
 */
public enum OrderType {
    INBOUND,
    OUTBOUND,
    COUNT,
    ADJUSTMENT
}
