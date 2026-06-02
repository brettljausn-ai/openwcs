package org.openwcs.orders.api;

import java.util.UUID;

/** Thrown when an order id cannot be found. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID id) {
        super("Order not found: " + id);
    }
}
