package org.openwcs.orders.domain;

/** Outbound order lifecycle states (build.md §4.6, §7 outbound process). */
public enum OrderStatus {
    CREATED,
    RELEASED,
    PARTIALLY_ALLOCATED,
    ALLOCATED,
    /** Released but stock could not be fully allocated; waits for instructions (retry/cancel). */
    NOT_FULFILLABLE,
    SHIPPED,
    CANCELLED
}
