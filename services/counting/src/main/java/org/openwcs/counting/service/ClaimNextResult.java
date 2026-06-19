package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Outcome of a claim-next call. {@code found} is false (and every other field null) when no eligible
 * line was available in the area; otherwise it carries the reserved line plus the parent task's
 * {@code countType} so the handheld knows whether to count blind.
 */
public record ClaimNextResult(
        boolean found,
        UUID countTaskId,
        UUID lineId,
        UUID locationId,
        UUID skuId,
        BigDecimal expectedQty,
        String uomCode,
        String countType) {

    /** A miss: nothing eligible in the area. */
    public static ClaimNextResult none() {
        return new ClaimNextResult(false, null, null, null, null, null, null, null);
    }
}
