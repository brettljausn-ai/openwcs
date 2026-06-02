package org.openwcs.orders.api;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Post a stock transaction against an order line. {@code qty} is the line-progress
 * contribution: positive for a receipt or pick, signed for a count/manual adjustment
 * (negative removes stock). The transaction type is derived from the order type.
 *
 * <p>{@code actor} (who performed it) is a <em>fallback</em>: the controller prefers the
 * authenticated user forwarded by the gateway ({@code X-Auth-User}). Audit requires an
 * actor either way — the service rejects a posting with neither.
 */
public record PostTransactionRequest(
        @NotNull BigDecimal qty,
        @NotNull UUID locationId,
        UUID huId,
        UUID batchId,
        String uomCode,
        String status,
        String actor) {
}
