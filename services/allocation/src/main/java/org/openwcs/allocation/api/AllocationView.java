package org.openwcs.allocation.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.allocation.domain.AllocationLine;
import org.openwcs.allocation.domain.OrderAllocation;
import org.openwcs.allocation.domain.Pick;
import org.openwcs.allocation.domain.ShipperAssignment;

/** Read model for an order's allocation + cube plan. */
public record AllocationView(
        UUID id,
        String orderRef,
        UUID warehouseId,
        String status,
        String statusDetail,
        String cubingMode,
        List<LineView> lines,
        List<ShipperAssignment> shippers) {

    public record LineView(
            int lineNo,
            UUID skuId,
            BigDecimal requestedQty,
            BigDecimal allocatedQty,
            String status,
            List<Pick> picks) {

        static LineView from(AllocationLine l) {
            return new LineView(l.getLineNo(), l.getSkuId(), l.getRequestedQty(),
                    l.getAllocatedQty(), l.getStatus(), l.getPicks());
        }
    }

    public static AllocationView from(OrderAllocation a) {
        List<LineView> lines = a.getLines().stream()
                .sorted((x, y) -> Integer.compare(x.getLineNo(), y.getLineNo()))
                .map(LineView::from)
                .toList();
        return new AllocationView(a.getId(), a.getOrderRef(), a.getWarehouseId(),
                a.getStatus(), a.getStatusDetail(), a.getCubingMode(), lines, a.getShippers());
    }
}
