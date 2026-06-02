package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Request to reserve (allocate) stock for an outbound order/wave (build.md §4.2, §7). */
public record ReserveCommand(
        UUID warehouseId,
        UUID skuId,
        BigDecimal qty,
        UUID batchId,
        UUID locationId,
        UUID huId,
        String orderRef,
        UUID correlationId,
        Instant expiresAt) {
}
