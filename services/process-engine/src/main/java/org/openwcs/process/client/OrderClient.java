package org.openwcs.process.client;

import java.util.UUID;

/** Port a BPMN service task uses to drive order-management (e.g. release an order for fulfilment). */
public interface OrderClient {
    void release(UUID orderId);
}
