package org.openwcs.allocation.api;

/** Thrown when no allocation exists for an order reference. */
public class AllocationNotFoundException extends RuntimeException {

    public AllocationNotFoundException(String orderRef) {
        super("No allocation for order: " + orderRef);
    }
}
