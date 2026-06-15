package org.openwcs.allocation.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Location-scoped stock availability + reservations from the inventory service. */
public interface InventoryClient {

    /** Available-to-promise for a SKU at a specific pick location. */
    BigDecimal availableAtLocation(UUID warehouseId, UUID skuId, UUID locationId);

    /**
     * Warehouse-wide available-to-promise for a SKU (across all locations, incl. reserve / ASRS).
     * Used by the stock-blocking report to tell recoverable shorts (stock exists somewhere) from
     * truly zero-stock shorts.
     */
    BigDecimal available(UUID warehouseId, UUID skuId);

    /** Reserve qty at a specific location; returns the reservation id. */
    UUID reserve(UUID warehouseId, UUID skuId, BigDecimal qty, UUID locationId, String orderRef);

    /** Release a reservation (compensation / not-fulfillable). */
    void release(UUID reservationId);
}
