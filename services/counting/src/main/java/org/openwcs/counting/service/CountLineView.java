package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.counting.domain.CountLine;

/**
 * A count line as presented to an operator. For a BLIND count {@code expectedQty} and
 * {@code variance} are withheld (null) until the count is reconciled; a VARIANCE count shows them.
 */
public record CountLineView(
        UUID countLineId,
        UUID locationId,
        UUID skuId,
        UUID batchId,
        String uomCode,
        BigDecimal expectedQty,
        BigDecimal countedQty,
        BigDecimal variance,
        String status,
        UUID adjustmentEventId) {

    public static CountLineView of(CountLine l, boolean blind) {
        return new CountLineView(
                l.getId(),
                l.getLocationId(),
                l.getSkuId(),
                l.getBatchId(),
                l.getUomCode(),
                blind ? null : l.getExpectedQty(),
                l.getCountedQty(),
                blind ? null : l.getVariance(),
                l.getStatus(),
                l.getAdjustmentEventId());
    }
}
