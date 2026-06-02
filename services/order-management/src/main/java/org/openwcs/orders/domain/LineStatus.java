package org.openwcs.orders.domain;

/** Order-line allocation states. */
public enum LineStatus {
    PENDING,
    ALLOCATED,
    SHORT,
    CANCELLED
}
