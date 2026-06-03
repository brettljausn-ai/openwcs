package org.openwcs.counting.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read seam onto the inventory availability projection: supplies the {@code expectedQty} snapshot
 * a count line is verified against. The counting service never writes inventory directly — it posts
 * adjustments via the transaction log ({@link TxLogClient}).
 */
public interface InventoryClient {

    /** On-hand quantity of a SKU at a specific location (0 if none / unavailable). */
    BigDecimal expectedOnHand(UUID warehouseId, UUID skuId, UUID locationId);
}
