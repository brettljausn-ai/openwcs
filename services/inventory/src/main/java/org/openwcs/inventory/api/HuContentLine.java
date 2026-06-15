package org.openwcs.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.inventory.domain.Stock;

/**
 * One line of the stock that rides in a handling unit, as seen by other services (flow,
 * picking) asking "what is on this HU?". Projected from the stock rows bound to the HU —
 * the HU's location is implied (the stock follows the HU), so it is not repeated per line.
 */
public record HuContentLine(
        UUID skuId,
        UUID batchId,
        BigDecimal qty,
        String uomCode,
        String status) {

    public static HuContentLine from(Stock s) {
        return new HuContentLine(s.getSkuId(), s.getBatchId(), s.getQty(), s.getUomCode(), s.getStatus());
    }
}
