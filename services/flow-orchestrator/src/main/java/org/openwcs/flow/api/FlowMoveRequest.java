package org.openwcs.flow.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to physically move a handling unit from one location to another (execution-layer item 1).
 * The orchestrator chooses the transport by where the locations live: a SAME-system / in-aisle move
 * (both in one storage block) is a single RELOCATE (AUTOSTORE → BIN_RELOCATE, everything else →
 * RELOCATE); a CROSS-system move (different storage blocks, or a storage slot → a conveyor / pick-face
 * on a different family) runs a RETRIEVE → CONVEY → STORE chain. {@code family} selects the source
 * equipment adapter; {@code destFamily} optionally overrides the destination adapter for the chain
 * STORE (defaults to {@code family}); {@code crossSystem} optionally forces either path (otherwise it
 * is inferred from the locations' storage blocks); {@code reason} is carried for the audit trail.
 */
public record FlowMoveRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID huId,
        String huCode,
        UUID fromLocationId,
        @NotNull UUID toLocationId,
        String family,
        String destFamily,
        Boolean crossSystem,
        String reason) {
}
