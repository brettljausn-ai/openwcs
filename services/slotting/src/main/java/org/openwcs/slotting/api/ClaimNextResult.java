package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.slotting.domain.ReplenishmentTask;

/**
 * Result of a claim-next: {@code found=false} when no eligible task is available in the area,
 * otherwise the reserved task's collection details. The reservation is already persisted by the time
 * this is returned.
 */
public record ClaimNextResult(
        boolean found,
        UUID taskId,
        UUID skuId,
        UUID fromLocationId,
        UUID toLocationId,
        BigDecimal qty,
        UUID uomId,
        String priority) {

    public static ClaimNextResult none() {
        return new ClaimNextResult(false, null, null, null, null, null, null, null);
    }

    public static ClaimNextResult of(ReplenishmentTask t) {
        return new ClaimNextResult(true, t.getId(), t.getSkuId(), t.getFromLocationId(),
                t.getToLocationId(), t.getQty(), t.getUomId(), t.getPriority());
    }
}
