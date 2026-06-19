package org.openwcs.orders.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outbound port to the inventory service for the available-at-location guard: before posting a
 * Picked transaction, order-management reads how much of the SKU is pickable at the pick face so
 * a pick can never drive stock negative (build.md §4.2). Best-effort: a read failure must not
 * block the pick (the caller decides how to degrade).
 */
public interface InventoryClient {

    /**
     * Available-to-promise summary at one location: {@code availableToPromise = onHand − reserved}
     * (on-hand counts only AVAILABLE stock). {@code availableToPromise} is the pickable quantity at
     * that face; {@code onHand} is the physical quantity sitting there.
     */
    Availability availabilityAtLocation(UUID warehouseId, UUID skuId, UUID locationId);

    record Availability(BigDecimal onHand, BigDecimal reserved, BigDecimal availableToPromise) {
    }
}
