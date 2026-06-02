package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a reservation requests more than the available-to-promise quantity. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(UUID skuId, BigDecimal requested, BigDecimal available) {
        super("Insufficient stock for SKU %s: requested %s, available-to-promise %s"
                .formatted(skuId, requested, available));
    }
}
