package org.openwcs.integration.host.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Canonical host-driven stock adjustment (signed delta against a bucket). Emitted as a
 * StockAdjusted event to the transaction log; the inventory projection applies it.
 */
public record InventoryAdjustment(
        @NotNull UUID warehouseId,
        @NotNull UUID skuId,
        UUID locationId,
        @NotNull BigDecimal qtyDelta,
        String uomCode,
        String status,
        String reason) {
}
