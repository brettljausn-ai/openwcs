package org.openwcs.slotting.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read access to the master-data catalog needed for slotting (ADR 0003). */
public interface MasterDataClient {

    /** Candidate storage locations in a block (the slotting pool), with their lane attributes. */
    List<StorageLocation> storageLocations(UUID warehouseId, UUID blockId);

    /** A storage block's slotting metadata, incl. its allowed-HU-types allow-list (null if unknown). */
    Block block(UUID blockId);

    /** All storage blocks of a warehouse (profile-less put-away resolves the only automated block). */
    List<Block> blocks(UUID warehouseId);

    /** A single location with its exact cell coordinate (null if unknown). */
    CellLocation location(UUID locationId);

    /** All locations of a warehouse, with their exact cell coordinates where present. */
    List<CellLocation> locations(UUID warehouseId);

    record StorageLocation(
            UUID id,
            String code,
            String purpose,
            String locationType,
            String aisle,
            Integer rackLevel,
            Integer laneDepth,
            Integer posX,
            Integer posY,
            Integer posZ,
            BigDecimal distanceToExit,
            Map<String, Object> capacity,
            String status,
            List<String> allowedHuTypes) {
    }

    record Block(
            UUID id,
            String storageType,
            String slottingGranularity,
            boolean gtp,
            List<String> allowedHuTypes) {
    }

    /**
     * A location with its exact cell coordinate (ADR 0009): {@code aisle} + {@code side} +
     * {@code posX} (along the aisle) + {@code posY} (vertical shuttle level) identify the channel,
     * {@code posZ} the depth within it (1 = aisle face … N = deepest).
     */
    record CellLocation(
            UUID id,
            UUID warehouseId,
            String code,
            String purpose,
            String status,
            String aisle,
            String side,
            Integer posX,
            Integer posY,
            Integer posZ) {

        /** True when the location carries a complete cell coordinate. */
        public boolean hasCell() {
            return posX != null && posY != null && posZ != null;
        }
    }
}
