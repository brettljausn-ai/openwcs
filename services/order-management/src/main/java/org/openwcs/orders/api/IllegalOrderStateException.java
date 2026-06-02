package org.openwcs.orders.api;

/** Thrown when a lifecycle transition is not allowed from the order's current state. */
public class IllegalOrderStateException extends RuntimeException {

    public IllegalOrderStateException(String message) {
        super(message);
    }
}
