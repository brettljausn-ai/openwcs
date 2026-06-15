package org.openwcs.slotting.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Location-scoped stock the slotting engines need (occupancy + pick-face on-hand). */
public interface InventoryClient {

    /** On-hand quantity of a SKU at a specific location (0 if none / unavailable). */
    BigDecimal onHandAtLocation(UUID warehouseId, UUID skuId, UUID locationId);

    /**
     * The subset of {@code locationIds} that physically hold any stock row or handling unit
     * (live occupancy, not the slotting ledger). Empty input returns an empty set.
     */
    Set<UUID> occupiedLocations(UUID warehouseId, List<UUID> locationIds);

    /** All handling units registered in a warehouse (the HU registry, incl. current location). */
    List<HandlingUnitView> handlingUnits(UUID warehouseId);

    /**
     * Current stock buckets for a SKU in a warehouse (per-location on-hand, with the holding HU and
     * batch). Replenishment source resolution scans these for a reserve location to draw from.
     * Empty on any error (best-effort).
     */
    List<StockBucket> stockForSku(UUID warehouseId, UUID skuId);

    /** A registry handling unit: where it physically is ({@code locationId} null while in transit). */
    record HandlingUnitView(UUID huId, String code, UUID locationId, String status) {
    }

    /** One stock bucket: a SKU/batch quantity at a location, optionally on a handling unit. */
    record StockBucket(UUID locationId, UUID huId, UUID batchId, String status, BigDecimal qty) {
    }
}
