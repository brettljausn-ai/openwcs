package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A request to place an inbound handling unit. {@code blockId} is optional — when omitted the
 * engine resolves the block from the SKU's storage profile. {@code uomId} narrows direct-to-pick
 * to a matching pick face.
 */
public record PutawayRequest(
        UUID warehouseId,
        UUID huId,
        UUID skuId,
        UUID batchId,
        UUID uomId,
        BigDecimal qty,
        UUID blockId) {
}
