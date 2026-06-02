package org.openwcs.orders.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OutboundOrder;

/** Read model for an outbound order and its lines. */
public record OrderView(
        UUID id,
        String orderRef,
        UUID warehouseId,
        String customerRef,
        String status,
        int priority,
        Instant dispatchBy,
        Instant createdAt,
        List<LineView> lines) {

    public record LineView(
            UUID id,
            int lineNo,
            UUID skuId,
            java.math.BigDecimal qty,
            java.math.BigDecimal allocatedQty,
            String status,
            UUID reservationId) {

        static LineView from(OrderLine l) {
            return new LineView(l.getId(), l.getLineNo(), l.getSkuId(), l.getQty(),
                    l.getAllocatedQty(), l.getStatus().name(), l.getReservationId());
        }
    }

    public static OrderView from(OutboundOrder o) {
        List<LineView> lines = o.getLines().stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .map(LineView::from)
                .toList();
        return new OrderView(o.getId(), o.getOrderRef(), o.getWarehouseId(), o.getCustomerRef(),
                o.getStatus().name(), o.getPriority(), o.getDispatchBy(), o.getCreatedAt(), lines);
    }
}
