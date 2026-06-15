package org.openwcs.flow.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to physically move a handling unit from one location to another (execution-layer item 1).
 * The orchestrator dispatches the device task(s) that carry the HU; v1 issues a single RELOCATE
 * (ADR-0009), which the emulator simulates. {@code family} selects the equipment adapter (AUTOSTORE
 * → BIN_RELOCATE, everything else → RELOCATE); {@code reason} is carried for the audit trail.
 */
public record FlowMoveRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID huId,
        String huCode,
        UUID fromLocationId,
        @NotNull UUID toLocationId,
        String family,
        String reason) {
}
