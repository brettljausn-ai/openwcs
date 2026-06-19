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

    /**
     * GoodsReceived (+) and Picked (−) — a quantity change at a single bucket.
     *
     * <p>{@code reservationId} is optional and only meaningful on Picked: when present it names the
     * order-line reservation to partial-consume alongside the stock decrement, so available-to-promise
     * (on-hand minus HELD reservation) is not double-counted. Absent (GoodsReceived, or a Picked with
     * no upstream reservation) it stays null and only stock moves (today's behavior). Other producer
     * fields (orderType, processInstanceId, actor) ride along as unknown JSON and are ignored.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BucketQty(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            UUID huId,
            String status,
            BigDecimal qty,
            String uomCode,
            UUID reservationId) {
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

    /**
     * StockAdjusted — signed delta against a bucket (cycle-count correction). {@code reason} is an
     * optional free-text audit note (e.g. "cycle-count", "damage", "shrinkage"); the projection math
     * ignores it, it only flows through so the cause of a reduction is recorded. Nullable so events
     * written before the field existed still deserialize.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Adjust(
            UUID warehouseId,
            UUID skuId,
            UUID batchId,
            UUID locationId,
            UUID huId,
            String status,
            BigDecimal qtyDelta,
            String uomCode,
            String reason) {
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
