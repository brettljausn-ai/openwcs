package org.openwcs.inventory.client;

import java.util.UUID;

/**
 * The slice of the master-data service the inventory service needs: resolving the per-warehouse
 * UNKNOWN operational location. HUs are always booked to a real location (product rule); when the
 * caller does not know where an HU is, it books to UNKNOWN instead of leaving the location null.
 */
public interface MasterDataClient {

    /**
     * The warehouse's UNKNOWN operational location id (lazily created by master-data on first use).
     *
     * @throws MasterDataUnavailableException when master-data cannot be reached
     */
    UUID unknownLocationId(UUID warehouseId);
}
