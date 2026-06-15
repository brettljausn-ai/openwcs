package org.openwcs.orders.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.orders.domain.LineStatus;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OrderLineTransaction;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.domain.TransactionType;

/**
 * One row in the operator pick queue: an outbound order line that is released/allocated and
 * still needs picking (build.md §7 outbound process). It is the RF-style guided-picking unit
 * of work — the operator walks to {@code locationId}, picks {@code remainingQty} of
 * {@code skuId}, and confirms.
 *
 * <p><b>Pick location:</b> {@code locationId} is the allocated primary pick location threaded
 * onto the order line on release/allocate (the face the allocation service reserved against).
 * It falls back to the most recent PICK transaction's location (where stock was actually taken
 * from once the first partial pick is posted), then to null when neither is known (e.g. a line
 * allocated before the location was threaded through). So the operator sees a real face before
 * any stock is picked.
 */
public record PickTaskView(
        UUID orderId,
        String orderCode,
        UUID lineId,
        int lineNo,
        UUID skuId,
        UUID locationId,
        BigDecimal requestedQty,
        BigDecimal pickedQty,
        BigDecimal remainingQty,
        String status) {

    /** What this line is expected to pick: the allocated (reserved) qty, falling back to ordered. */
    public static BigDecimal requestedQtyOf(OrderLine line) {
        return line.getAllocatedQty().signum() > 0 ? line.getAllocatedQty() : line.getQty();
    }

    /** How much has been picked so far: the sum of PICK postings (postedQty for an outbound line). */
    public static BigDecimal pickedQtyOf(OrderLine line) {
        return line.getPostedQty();
    }

    /**
     * Best-effort pick location: the allocated primary pick location threaded onto the line,
     * else the location of the latest PICK transaction (where stock was actually taken from),
     * else null.
     */
    public static UUID pickLocationOf(OrderLine line) {
        if (line.getPickLocationId() != null) {
            return line.getPickLocationId();
        }
        return line.getTransactions().stream()
                .filter(t -> t.getTxnType() == TransactionType.PICK && t.getLocationId() != null)
                .reduce((first, second) -> second) // last one
                .map(OrderLineTransaction::getLocationId)
                .orElse(null);
    }

    public static PickTaskView from(OutboundOrder order, OrderLine line) {
        BigDecimal requested = requestedQtyOf(line);
        BigDecimal picked = pickedQtyOf(line);
        BigDecimal remaining = requested.subtract(picked).max(BigDecimal.ZERO);
        return new PickTaskView(
                order.getId(), order.getOrderRef(), line.getId(), line.getLineNo(), line.getSkuId(),
                pickLocationOf(line), requested, picked, remaining, line.getStatus().name());
    }
}
