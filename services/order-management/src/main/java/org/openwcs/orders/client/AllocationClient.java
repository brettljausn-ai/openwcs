package org.openwcs.orders.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port to the allocation service (ADR 0002). order-management delegates the
 * pick-location allocation + cubing and only records the resulting fulfilment status.
 */
public interface AllocationClient {

    AllocationResult allocate(String orderRef, UUID warehouseId, List<Line> lines);

    /** Cancel an order's allocation, releasing any held reservations. No-op if none exists. */
    void cancel(String orderRef);

    record Line(int lineNo, UUID skuId, BigDecimal qty) {
    }

    /**
     * {@code FULFILLABLE} when every line was reserved against pick locations. {@code detail}
     * carries the reason for a non-fulfillable outcome (e.g. why cubing failed), for the UI.
     */
    record AllocationResult(String status, String detail) {
        public boolean fulfillable() {
            return "FULFILLABLE".equals(status);
        }

        public boolean cubingFailed() {
            return "CUBING_FAILED".equals(status);
        }
    }
}
