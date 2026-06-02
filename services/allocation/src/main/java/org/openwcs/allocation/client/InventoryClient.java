package org.openwcs.allocation.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Location-scoped stock availability + reservations from the inventory service. */
public interface InventoryClient {

    /** Available-to-promise for a SKU at a specific pick location. */
    BigDecimal availableAtLocation(UUID warehouseId, UUID skuId, UUID locationId);

    /** Reserve qty at a specific location; returns the reservation id. */
    UUID reserve(UUID warehouseId, UUID skuId, BigDecimal qty, UUID locationId, String orderRef);

    /** Release a reservation (compensation / not-fulfillable). */
    void release(UUID reservationId);
}
