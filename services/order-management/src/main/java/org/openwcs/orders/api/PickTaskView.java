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
 * <p><b>Pick location (gap):</b> the allocation service assigns the pick location, but its
 * {@code LineResult} only returns the allocated quantity + status (no location), and the
 * order line carries no per-line pick location. So {@code locationId} is sourced best-effort
 * from the most recent PICK transaction posted against the line (set once the first partial
 * pick records where stock was taken from) and is otherwise null. Wiring the allocated
 * location through from the allocation service is the documented follow-up.
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

    /** Best-effort pick location: the location of the latest PICK transaction, else null (see gap). */
    public static UUID pickLocationOf(OrderLine line) {
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
