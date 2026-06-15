package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Request to seed demo DASHBOARD inventory (build.md §4.8): stocked + empty HUs for occupancy /
 * utilisation, a set of HUs received-and-stored today (dock-to-stock timing) and a couple still in
 * the put-away backlog (parked at receiving). The UI passes the existing master-data ids: the
 * {@code warehouseId}, the demo {@code huTypeId} (may be null), the storage {@code locationIds}, the
 * {@code receivingLocationIds} (backlog parking; may be empty) and the seeded {@code skuIds}.
 */
public record DemoDashboardSeedRequest(
        UUID warehouseId,
        UUID huTypeId,
        List<UUID> locationIds,
        List<UUID> receivingLocationIds,
        List<UUID> skuIds) {
}
