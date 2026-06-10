package org.openwcs.flow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to present an HU at a workplace (ADR-0007 §3.1). Flow creates a REQUESTED induction entry
 * and orchestrates the journey itself (RETRIEVE then, on its callback, CONVEY).
 */
public record InductionRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID workplaceId,
        String workplaceKind,
        @NotNull UUID huId,
        String huCode,
        UUID skuId,
        String skuCode,
        BigDecimal qty,
        UUID locationId,
        @NotBlank String mode,
        @NotBlank String family,
        UUID countTaskId,
        UUID countLineId) {
}
