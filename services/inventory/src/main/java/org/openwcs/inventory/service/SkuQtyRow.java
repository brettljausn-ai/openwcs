package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.UUID;

/** One SKU's aggregated quantity (JPQL constructor-expression target for the report queries). */
public record SkuQtyRow(UUID skuId, BigDecimal qty) {
}
