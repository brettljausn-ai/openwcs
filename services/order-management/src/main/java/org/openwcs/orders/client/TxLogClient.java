package org.openwcs.orders.client;

import java.util.Map;
import java.util.UUID;

/** Appends stock-transaction events to the transaction log (build.md §5.2). */
public interface TxLogClient {

    /**
     * Append one event and return its id.
     *
     * @param streamId      aggregate stream (the order line id)
     * @param eventType     GoodsReceived | Picked | StockAdjusted
     * @param correlationId owning order id
     * @param payload       event-specific body (matches the inventory projection payloads)
     */
    UUID append(String streamId, String eventType, UUID correlationId, String actor, Map<String, Object> payload);
}
