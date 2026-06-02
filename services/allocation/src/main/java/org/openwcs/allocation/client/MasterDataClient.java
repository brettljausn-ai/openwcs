package org.openwcs.allocation.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Read access to the master-data catalog needed for allocation + cubing (ADR 0002). */
public interface MasterDataClient {

    FulfillmentConfig fulfillmentConfig(UUID warehouseId);

    /** Ids of PICK-purpose locations in the warehouse (candidate pick faces). */
    List<UUID> pickLocationIds(UUID warehouseId);

    List<ShipperDef> shippers(UUID warehouseId);

    List<UomDef> skuUoms(UUID skuId);

    record FulfillmentConfig(
            List<String> allowedPickTypes,
            String cubingMode,
            UUID defaultShipperId,
            boolean batchEnabled,
            int batchMaxPieces,
            int batchMaxOrders,
            UUID pickToteShipperId) {
    }

    record ShipperDef(
            UUID id, String code, BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm,
            BigDecimal tareWeightG, BigDecimal maxFillLevel, BigDecimal maxWeightG, String status) {
    }

    record UomDef(
            UUID id, String code, BigDecimal qtyInParent, UUID parentUomId,
            BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm, BigDecimal weightG, boolean baseUnit) {
    }
}
