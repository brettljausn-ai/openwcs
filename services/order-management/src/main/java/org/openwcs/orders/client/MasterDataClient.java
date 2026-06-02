package org.openwcs.orders.client;

/**
 * Outbound port to master-data for validating dispatch reference codes on an order: the
 * shipping-service catalog (EXPRESS/STANDARD) and the route catalog (regions/depots).
 */
public interface MasterDataClient {

    /** True if an ACTIVE shipping service with this code exists. */
    boolean shippingServiceExists(String code);

    /** True if an ACTIVE route with this code exists. */
    boolean routeExists(String code);
}
