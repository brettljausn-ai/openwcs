package org.openwcs.processdesigner.verify;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request to the scan-verify proxy ({@code POST /api/process-designer/verify}). The handheld runtime
 * posts a scanned/typed value to be resolved against master-data so a flow can confirm it exists and
 * branch on / store the linked ids. {@code kind} selects the master-data resolve endpoint:
 * {@code barcode} (match a barcode to its SKU), {@code sku} (match a SKU by code), or
 * {@code location} (match a location by code).
 */
public record VerifyRequest(
        @NotNull UUID warehouseId,
        @NotBlank String kind,
        @NotBlank String code) {
}
