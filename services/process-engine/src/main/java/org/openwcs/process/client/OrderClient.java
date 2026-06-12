package org.openwcs.process.client;

import java.util.UUID;

/** Port a BPMN service task uses to drive order-management (e.g. release an order for fulfilment). */
public interface OrderClient {
    void release(UUID orderId);

    /**
     * Short allocate and release a NOT_FULFILLABLE order: pick the available quantity and ship
     * short (the explicit supervisor decision, re-driven through the process).
     */
    void releaseShort(UUID orderId);
}
