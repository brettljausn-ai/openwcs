package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Open an order destination at an ORDER node: bind an order HU and post the demand (a SKU and
 * how many units must be put there). Demand normally originates from allocation/order-management
 * (referenced by UUID — this is the integration seam).
 */
public record OpenDestinationRequest(
        @NotNull UUID orderHuId,
        @NotBlank String orderRef,
        UUID orderLineId,
        @NotNull UUID skuId,
        @NotNull @Positive BigDecimal qty) {
}
