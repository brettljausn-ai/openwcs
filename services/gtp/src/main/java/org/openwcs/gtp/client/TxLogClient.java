package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write seam onto the transaction log: posts a {@code StockAdjusted} event so the inventory stock
 * projection applies a signed {@code qtyDelta} to the bucket. The gtp service uses this for the
 * broken-product operator exception (a negative DAMAGED adjustment on the tote's SKU).
 */
public interface TxLogClient {

    /**
     * Append a {@code StockAdjusted} event (the inventory adjustment path) and return its event id.
     * {@code qtyDelta} is the signed correction in the SKU base UoM.
     */
    UUID postStockAdjusted(StockAdjustment adjustment);

    /** The fields the StockAdjusted txlog payload (inventory's Adjust record) carries. */
    record StockAdjustment(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            BigDecimal qtyDelta,
            String uomCode,
            String reason,
            String actor) {
    }
}
