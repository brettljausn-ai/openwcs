package org.openwcs.orders.api;

import java.util.UUID;
import org.openwcs.orders.domain.OutboundOrder;

/**
 * Scan-verification result for resolving an order / ASN by its reference (barcode). A process
 * flow branches on {@code found}: a no-match resolves to {@code found:false} with a null order
 * (HTTP 200, not 404) so the flow can take a "not found" branch. The summary carries just what a
 * verify step needs to branch (orderType, status) plus the line count; the full order is fetched
 * separately by id when details are needed.
 */
public record ResolveOrderResult(boolean found, OrderSummary order) {

    /** Minimal order summary for scan verification. */
    public record OrderSummary(
            UUID orderId,
            String orderRef,
            String orderType,
            String status,
            String customerRef,
            int lineCount) {

        public static OrderSummary from(OutboundOrder o) {
            return new OrderSummary(
                    o.getId(),
                    o.getOrderRef(),
                    o.getOrderType().name(),
                    o.getStatus().name(),
                    o.getCustomerRef(),
                    o.getLines().size());
        }
    }

    /** A confirmed resolution: found with the order summary. */
    public static ResolveOrderResult of(OutboundOrder o) {
        return new ResolveOrderResult(true, OrderSummary.from(o));
    }

    /** A no-match resolution: found:false, order:null. */
    public static ResolveOrderResult notFound() {
        return new ResolveOrderResult(false, null);
    }
}
