package org.openwcs.inventory.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.inventory.service.ReserveCommand;

/** Request body to reserve stock. */
public record ReserveRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID skuId,
        @NotNull @Positive BigDecimal qty,
        UUID batchId,
        UUID locationId,
        UUID huId,
        String orderRef,
        UUID correlationId,
        Instant expiresAt) {

    public ReserveCommand toCommand() {
        return new ReserveCommand(
                warehouseId, skuId, qty, batchId, locationId, huId, orderRef, correlationId, expiresAt);
    }
}
