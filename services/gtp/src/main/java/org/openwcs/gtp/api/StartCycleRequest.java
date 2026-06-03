package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Open/start a work cycle in a given operating mode and present its HU(s), supplying the
 * mode-appropriate task lines:
 *
 * <ul>
 *   <li>PICKING — prefer {@code POST /stations/{id}/present}; this endpoint also accepts PICKING for
 *       symmetry (ignores {@code lines}, matching demand as usual). Requires {@code skuId}+{@code qty}.</li>
 *   <li>DECANTING — present a source {@code stockHuId} and an empty {@code targetHuId}; each line is
 *       a decant-move: {@code skuId} + {@code qty} into {@code compartment} of the target HU.</li>
 *   <li>STOCK_COUNT — present {@code stockHuId}; each line is a count entry: {@code skuId} +
 *       {@code expectedQty} (the system qty to count against).</li>
 *   <li>QC — present {@code stockHuId}; each line is a verdict slot per {@code skuId}
 *       (or one HU-level line with no SKU).</li>
 *   <li>MAINTENANCE — request {@code stockHuId} (an HU or empty carrier); each line is a check slot
 *       (no SKU needed).</li>
 * </ul>
 */
public record StartCycleRequest(
        @NotBlank String operatingMode,
        UUID stockNodeId,
        UUID stockHuId,
        UUID targetHuId,
        UUID skuId,
        BigDecimal qty,
        List<LineSpec> lines) {

    /** One mode-appropriate task-line to create (interpreted per the cycle's operating mode). */
    public record LineSpec(
            UUID huId,
            UUID skuId,
            String compartment,
            BigDecimal qty,
            String putLightId) {
    }
}
