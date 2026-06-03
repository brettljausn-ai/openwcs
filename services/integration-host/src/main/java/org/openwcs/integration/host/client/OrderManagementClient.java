package org.openwcs.integration.host.client;

import java.util.Map;

/** Port to order-management for creating orders from host requests. */
public interface OrderManagementClient {

    /** Create an order (body already in order-management's shape); returns the created identifiers. */
    CreatedOrder createOrder(Map<String, Object> orderBody);

    record CreatedOrder(String id, String orderRef, String status) {
    }
}
