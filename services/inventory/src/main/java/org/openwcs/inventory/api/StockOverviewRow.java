package org.openwcs.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Read model for one warehouse-wide stock-overview bucket (on-hand, reserved, available). */
public record StockOverviewRow(
        UUID skuId,
        UUID locationId,
        UUID huId,
        String huCode,
        String status,
        BigDecimal qty,
        BigDecimal reserved,
        BigDecimal available) {
}
