package org.openwcs.slotting.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Location-scoped stock the slotting engines need (occupancy + pick-face on-hand). */
public interface InventoryClient {

    /** On-hand quantity of a SKU at a specific location (0 if none / unavailable). */
    BigDecimal onHandAtLocation(UUID warehouseId, UUID skuId, UUID locationId);
}
