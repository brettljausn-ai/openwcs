package org.openwcs.process.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Port a BPMN service task uses to allocate (and cube) an outbound order via the allocation
 * service (ADR 0002).
 */
public interface AllocationClient {

    /**
     * Allocate + cube the given order. Returns the resulting plan, whose {@code status} is either
     * {@code FULFILLABLE} or {@code NOT_FULFILLABLE}; idempotent for an already-FULFILLABLE order.
     */
    Allocation allocate(String orderRef, UUID warehouseId, List<Line> lines);

    /** An order line to allocate. */
    record Line(int lineNo, UUID skuId, BigDecimal qty) {
    }

    /** The allocation result the process needs to branch on. */
    record Allocation(String orderRef, String status, int shipperCount) {
    }
}
