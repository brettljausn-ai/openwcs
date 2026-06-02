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

    record Line(int lineNo, UUID skuId, BigDecimal qty) {
    }

    /** {@code FULFILLABLE} when every line was reserved against pick locations. */
    record AllocationResult(String status) {
        public boolean fulfillable() {
            return "FULFILLABLE".equals(status);
        }
    }
}
