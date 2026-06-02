package org.openwcs.inventory.projection;

/**
 * Transaction-log {@code eventType} values the stock projection reacts to
 * (build.md §5.2, §7). The streamed log (topic {@code txlog.stream}) carries every
 * event; the projection applies these and ignores the rest.
 */
public final class InventoryEventTypes {

    /** Goods-in: stock created/incremented at a receiving (or putaway) location. */
    public static final String GOODS_RECEIVED = "GoodsReceived";

    /** Putaway leg of a move (receiving/staging → storage). Uses the move payload. */
    public static final String PUTAWAY_COMPLETED = "PutawayCompleted";

    /** Generic move between locations / handling units. */
    public static final String STOCK_MOVED = "StockMoved";

    /** Outbound pick: stock decremented from a source bucket. */
    public static final String PICKED = "Picked";

    /** Cycle-count / correction: signed delta applied to a bucket (compensating event). */
    public static final String STOCK_ADJUSTED = "StockAdjusted";

    /** Lock/unlock/quality move: quantity shifted between status buckets at one place. */
    public static final String STOCK_STATUS_CHANGED = "StockStatusChanged";

    private InventoryEventTypes() {
    }
}
