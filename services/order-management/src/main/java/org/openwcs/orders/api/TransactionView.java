package org.openwcs.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.orders.domain.OrderLineTransaction;

/** Read model for one stock transaction beneath a line. */
public record TransactionView(
        UUID id,
        String txnType,
        BigDecimal qty,
        UUID locationId,
        UUID huId,
        UUID batchId,
        UUID eventId,
        String actor,
        Instant postedAt) {

    public static TransactionView from(OrderLineTransaction t) {
        return new TransactionView(t.getId(), t.getTxnType().name(), t.getQty(), t.getLocationId(),
                t.getHuId(), t.getBatchId(), t.getEventId(), t.getActor(), t.getPostedAt());
    }
}
