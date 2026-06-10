package org.openwcs.counting.client;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read seam onto master-data used by ASRS count-tote routing: the global hardware-emulator flag,
 * a location's storage block, and a SKU's code. Routing only proceeds when the emulator is ON and
 * the cell's block is an ASRS-family automated system.
 */
public interface MasterDataClient {

    /** ASRS-family storage types (automated systems whose stock lives in handling units). */
    Set<String> ASRS_FAMILY = Set.of("SHUTTLE_ASRS", "CRANE_ASRS", "AUTOSTORE", "AMR_GTP");

    /** Whether hardware-emulator mode is ON (simulated equipment). false on any failure. */
    boolean emulatorEnabled();

    /** The {@code storageType} of the storage block a location belongs to, if resolvable. */
    Optional<String> storageTypeOfLocation(UUID warehouseId, UUID locationId);

    /** A SKU's host code, if resolvable (used to label the transport / queue entry). */
    Optional<String> skuCode(UUID skuId);

    /** True when a storage type is an ASRS-family automated system. */
    static boolean isAsrsFamily(String storageType) {
        return storageType != null && ASRS_FAMILY.contains(storageType);
    }

    /**
     * Map a storage-block type to the device-adapter family that services it (the flow-orchestrator
     * adapter key: {@code ASRS | AUTOSTORE | AMR | CONVEYOR}). Returns {@code null} for non-automated
     * storage ({@code MANUAL_PICK}, {@code RESERVE_RACK}) which has no device family. Shuttle and crane
     * ASRS share the one {@code ASRS} adapter.
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
