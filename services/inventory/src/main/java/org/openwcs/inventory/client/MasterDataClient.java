package org.openwcs.inventory.client;

import java.util.List;
import java.util.UUID;

/**
 * The slice of the master-data service the inventory service needs: resolving the per-warehouse
 * UNKNOWN operational location (HUs are always booked to a real location; position-less bookings
 * go to UNKNOWN) and the warehouse / storage-block / location topology the storage-density
 * sweeper measures fill levels against.
 */
public interface MasterDataClient {

    /**
     * The warehouse's UNKNOWN operational location id (lazily created by master-data on first use).
     *
     * @throws MasterDataUnavailableException when master-data cannot be reached
     */
    UUID unknownLocationId(UUID warehouseId);

    /**
     * All warehouse ids (the storage-density sweeper snapshots every warehouse).
     *
     * @throws MasterDataUnavailableException when master-data cannot be reached
     */
    List<UUID> warehouseIds();

    /**
     * The ids of the warehouse's storage blocks.
     *
     * @throws MasterDataUnavailableException when master-data cannot be reached
     */
    List<UUID> storageBlockIds(UUID warehouseId);

    /**
     * The ids of all locations (cells) belonging to one storage block.
     *
     * @throws MasterDataUnavailableException when master-data cannot be reached
     */
    List<UUID> blockLocationIds(UUID warehouseId, UUID blockId);
}
