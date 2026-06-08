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

    /** True if an ACTIVE label template with this code exists. */
    boolean labelTemplateExists(String code);

    /** The label-template code configured on a shipping service, or null. */
    String serviceLabelTemplate(String serviceCode);

    /** The warehouse's default label-template code (from fulfillment config), or null. */
    String warehouseDefaultLabelTemplate(java.util.UUID warehouseId);

    /** Ids of the seeded demo SKUs (ownerClient=DEMO). Empty when demo mode has not been enabled. */
    java.util.List<java.util.UUID> listDemoSkus();
}
