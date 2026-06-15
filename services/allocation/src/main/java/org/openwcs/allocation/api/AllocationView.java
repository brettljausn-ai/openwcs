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
            UUID pickLocationId,
            List<Pick> picks) {

        static LineView from(AllocationLine l) {
            return new LineView(l.getLineNo(), l.getSkuId(), l.getRequestedQty(),
                    l.getAllocatedQty(), l.getStatus(), primaryPickLocation(l), l.getPicks());
        }

        /**
         * The primary pick location for the line: the location of the first pick. Picks are
         * appended in pick-location iteration order (the first location with available stock),
         * so the first pick is the head of the pick walk. When a line is split across multiple
         * locations only this primary face is threaded onto the order line for the operator pick
         * queue (the full per-location breakdown stays in {@code picks}). Null when nothing was
         * reserved (e.g. a fully-short line). Only the locationId is carried — the allocation
         * domain does not hold a human-readable location code, so none is returned here.
         */
        private static UUID primaryPickLocation(AllocationLine l) {
            return l.getPicks().stream()
                    .map(Pick::locationId)
                    .filter(java.util.Objects::nonNull)
                    .findFirst()
                    .orElse(null);
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
