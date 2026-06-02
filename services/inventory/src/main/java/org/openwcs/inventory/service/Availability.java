package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Available-to-promise summary for a SKU in a warehouse (build.md §4.2):
 * {@code availableToPromise = onHand − reserved}, where on-hand counts only AVAILABLE
 * stock (locked/quarantine/damaged buckets are excluded).
 */
public record Availability(
        UUID warehouseId,
        UUID skuId,
        BigDecimal onHand,
        BigDecimal reserved,
        BigDecimal availableToPromise) {
}
