package org.openwcs.orders.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port to the allocation service (ADR 0002). order-management delegates the
 * pick-location allocation + cubing and only records the resulting fulfilment status
 * (plus the per-line allocated quantities, so short lines stay visible on the order).
 */
public interface AllocationClient {

    /**
     * Allocate (and cube) the order. {@code allowShort} relays the explicit supervisor
     * decision to short allocate: the available qty per line is reserved and the order
     * comes back {@code FULFILLABLE_SHORT} instead of {@code NOT_FULFILLABLE}.
     */
    AllocationResult allocate(String orderRef, UUID warehouseId, List<Line> lines, Dispatch dispatch,
                              boolean allowShort);

    /** Cancel an order's allocation, releasing any held reservations. No-op if none exists. */
    void cancel(String orderRef);

    record Line(int lineNo, UUID skuId, BigDecimal qty) {
    }

    /** Shared dispatch-label context for the order; allocation builds a per-shipper label from it. */
    record Dispatch(org.openwcs.orders.domain.ShipToAddress shipTo, String serviceCode, String routeCode,
                    String labelTemplateCode) {
    }

    /**
     * {@code FULFILLABLE} when every line was reserved against pick locations;
     * {@code FULFILLABLE_SHORT} when an allow-short run reserved what it could. {@code detail}
     * carries the reason for a non-fulfillable outcome (e.g. why cubing failed) or the
     * shortfall summary, for the UI. {@code lines} carries the per-line allocated quantities.
     */
    record AllocationResult(String status, String detail, List<LineResult> lines) {
        public boolean fulfillable() {
            return "FULFILLABLE".equals(status);
        }

        public boolean shortFulfillable() {
            return "FULFILLABLE_SHORT".equals(status);
        }

        public boolean cubingFailed() {
            return "CUBING_FAILED".equals(status);
        }
    }

    /** Per-line allocation outcome: how much of the line is reserved ({@code ALLOCATED} or {@code SHORT}). */
    record LineResult(int lineNo, BigDecimal allocatedQty, String status) {
    }
}
