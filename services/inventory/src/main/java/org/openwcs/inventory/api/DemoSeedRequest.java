package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * Request to seed demo handling units + stock into the inventory service (build.md §4.8).
 * The UI passes the existing master-data ids: the target {@code warehouseId}, the demo
 * handling-unit type ({@code huTypeId}, may be null), the warehouse {@code locationIds}, and
 * the seeded {@code skuIds}. Seeding is skipped (returns zeros) when locations or SKUs are empty.
 */
public record DemoSeedRequest(UUID warehouseId, UUID huTypeId, List<UUID> locationIds, List<UUID> skuIds) {}
