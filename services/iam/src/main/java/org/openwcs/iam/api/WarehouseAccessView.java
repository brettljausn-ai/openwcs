package org.openwcs.iam.api;

import java.util.List;
import java.util.UUID;

/**
 * A user's warehouse access: the warehouses they may work in and which one is their default
 * (null if none is flagged). Warehouse IDs are master-data UUIDs; the UI resolves names from
 * master-data. Matches the shape consumed by ui/src/auth (allowedWarehouses + defaultWarehouseId).
 */
public record WarehouseAccessView(List<UUID> warehouses, UUID defaultWarehouse) {
}
