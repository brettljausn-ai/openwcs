package org.openwcs.gtp.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Read seam onto master-data used by store-back: resolve the storage block a put-away location
 * belongs to so the STORE transport is dispatched to the device-adapter family that actually
 * services that storage (ASRS / AutoStore / AMR) rather than a hardcoded one.
 */
public interface MasterDataClient {

    /** The {@code storageType} of the storage block a location belongs to, if resolvable. */
    Optional<String> storageTypeOfLocation(UUID warehouseId, UUID locationId);

    /**
     * Map a storage-block type to the device-adapter family that services it (the flow-orchestrator
     * adapter key: {@code ASRS | AUTOSTORE | AMR | CONVEYOR}). Returns {@code null} for non-automated
     * storage ({@code MANUAL_PICK}, {@code RESERVE_RACK}). Shuttle and crane ASRS share one adapter.
     */
    static String deviceFamilyOf(String storageType) {
        if (storageType == null) {
            return null;
        }
        return switch (storageType) {
            case "SHUTTLE_ASRS", "CRANE_ASRS" -> "ASRS";
            case "AUTOSTORE" -> "AUTOSTORE";
            case "AMR_GTP" -> "AMR";
            default -> null;
        };
    }
}
