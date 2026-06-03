package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Present a stock HU (a SKU and the qty available in it) at a station, optionally at a specific
 * STOCK node. The service matches the SKU against open demand across the station's ORDER nodes
 * and returns the generated put-list (the goods-to-person batch — one stock HU, many puts).
 */
public record PresentStockRequest(
        UUID stockNodeId,
        @NotNull UUID stockHuId,
        @NotNull UUID skuId,
        @NotNull @Positive BigDecimal qty) {
}
