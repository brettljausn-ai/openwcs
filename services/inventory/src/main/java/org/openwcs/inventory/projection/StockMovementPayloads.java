package org.openwcs.inventory.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Typed payloads carried by the transaction-log envelope for stock-affecting events.
 * Quantities are assumed normalized to the SKU base UoM by the producer (build.md §12);
 * {@code uomCode} is the base-unit label stored on the stock row. Unknown JSON fields
 * are ignored so payloads can evolve without breaking older consumers.
 */
public final class StockMovementPayloads {

    private StockMovementPayloads() {
    }

    /** GoodsReceived (+) and Picked (−) — a quantity change at a single bucket. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketQty(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            UUID huId,
            String status,
            BigDecimal qty,
            String uomCode) {
    }

    /** PutawayCompleted / StockMoved — quantity moved from one place to another. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Move(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            BigDecimal qty,
            String uomCode,
            String status,
            UUID fromLocationId,
            UUID fromHuId,
            UUID toLocationId,
            UUID toHuId) {
    }

    /** StockAdjusted — signed delta against a bucket (cycle-count correction). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Adjust(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            UUID huId,
            String status,
            BigDecimal qtyDelta,
            String uomCode) {
    }

    /** StockStatusChanged — move quantity between status buckets at one place (lock/unlock). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatusChange(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            UUID huId,
            BigDecimal qty,
            String uomCode,
            String fromStatus,
            String toStatus) {
    }
}
