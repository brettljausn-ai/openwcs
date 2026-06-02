package org.openwcs.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.inventory.domain.Stock;

/** Read model for one stock bucket. */
public record StockView(
        UUID stockId,
        UUID warehouseId,
        UUID skuId,
        UUID batchId,
        UUID locationId,
        UUID huId,
        String status,
        BigDecimal qty,
        String uomCode) {

    public static StockView from(Stock s) {
        return new StockView(
                s.getId(), s.getWarehouseId(), s.getSkuId(), s.getBatchId(),
                s.getLocationId(), s.getHuId(), s.getStatus(), s.getQty(), s.getUomCode());
    }
}
