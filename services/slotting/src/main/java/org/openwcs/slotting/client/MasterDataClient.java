package org.openwcs.slotting.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read access to the master-data catalog needed for slotting (ADR 0003). */
public interface MasterDataClient {

    /** Candidate storage locations in a block (the slotting pool), with their lane attributes. */
    List<StorageLocation> storageLocations(UUID warehouseId, UUID blockId);

    record StorageLocation(
            UUID id,
            String code,
            String purpose,
            String locationType,
            String aisle,
            Integer rackLevel,
            Integer laneDepth,
            BigDecimal distanceToExit,
            Map<String, Object> capacity,
            String status) {
    }
}
