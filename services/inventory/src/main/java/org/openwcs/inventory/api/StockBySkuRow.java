package org.openwcs.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One SKU's stock split for the Reporting screen: usable now (available), held by
 * reservations (allocated), and physically present but unusable (unavailable =
 * non-AVAILABLE status plus anything parked at the warehouse's UNKNOWN location).
 */
public record StockBySkuRow(
        UUID skuId,
        BigDecimal available,
        BigDecimal allocated,
        BigDecimal unavailable) {
}
