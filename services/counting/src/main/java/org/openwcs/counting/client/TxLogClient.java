package org.openwcs.counting.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write seam onto the transaction log: posts a {@code StockAdjusted} event for an approved count
 * variance. The inventory service's stock projection consumes the streamed log and applies the
 * signed {@code qtyDelta} to the bucket — so counting corrects inventory without touching its store.
 */
public interface TxLogClient {

    /**
     * Append a {@code StockAdjusted} event (the inventory adjustment path) and return its event id.
     * {@code qtyDelta} is the signed correction (counted − expected) in the SKU base UoM.
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
            UUID countTaskId,
            UUID countLineId,
            String actor) {
    }
}
