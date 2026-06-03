package org.openwcs.orders.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.orders.domain.OrderLine;
import org.openwcs.orders.domain.OutboundOrder;

/** Read model for an order and its lines (with each line's posted stock transactions). */
public record OrderView(
        UUID id,
        String orderRef,
        String orderType,
        UUID warehouseId,
        String customerRef,
        String status,
        String statusDetail,
        String serviceCode,
        String routeCode,
        org.openwcs.orders.domain.ShipToAddress shipTo,
        String labelTemplateCode,
        int priority,
        Instant dispatchBy,
        Instant createdAt,
        List<LineView> lines) {

    public record LineView(
            UUID id,
            int lineNo,
            UUID skuId,
            BigDecimal qty,
            BigDecimal allocatedQty,
            BigDecimal postedQty,
            String status,
            UUID reservationId,
            List<TransactionView> transactions) {

        static LineView from(OrderLine l) {
            List<TransactionView> txns = l.getTransactions().stream()
                    .sorted((a, b) -> a.getPostedAt().compareTo(b.getPostedAt()))
                    .map(TransactionView::from)
                    .toList();
            return new LineView(l.getId(), l.getLineNo(), l.getSkuId(), l.getQty(),
                    l.getAllocatedQty(), l.getPostedQty(), l.getStatus().name(), l.getReservationId(), txns);
        }
    }

    public static OrderView from(OutboundOrder o) {
        List<LineView> lines = o.getLines().stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .map(LineView::from)
                .toList();
        return new OrderView(o.getId(), o.getOrderRef(), o.getOrderType().name(), o.getWarehouseId(),
                o.getCustomerRef(), o.getStatus().name(), o.getStatusDetail(),
                o.getServiceCode(), o.getRouteCode(), o.getShipTo(), o.getLabelTemplateCode(),
                o.getPriority(), o.getDispatchBy(), o.getCreatedAt(), lines);
    }
}
