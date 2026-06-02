package org.openwcs.orders.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Post a stock transaction against an order line. {@code qty} is the line-progress
 * contribution: positive for a receipt or pick, signed for a count/manual adjustment
 * (negative removes stock). The transaction type is derived from the order type.
 *
 * <p>{@code actor} (who performed it) is required for audit — every stock change records
 * who caused it. Until IAM/JWT is wired it is caller-asserted; the gateway/IAM will later
 * supply the authenticated principal.
 */
public record PostTransactionRequest(
        @NotNull BigDecimal qty,
        @NotNull UUID locationId,
        UUID huId,
        UUID batchId,
        String uomCode,
        String status,
        @NotBlank String actor) {
}
