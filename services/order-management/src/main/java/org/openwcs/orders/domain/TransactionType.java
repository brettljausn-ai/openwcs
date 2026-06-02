package org.openwcs.orders.domain;

/** The kind of stock posting beneath an order line. */
public enum TransactionType {
    RECEIPT,
    PICK,
    COUNT,
    ADJUSTMENT
}
