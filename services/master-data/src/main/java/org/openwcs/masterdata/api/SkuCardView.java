package org.openwcs.masterdata.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * One-call "product card" read for operator screens (e.g. the GTP tote panel): the SKU identity
 * plus its base-unit dimensions and the per-warehouse profile metadata, so the UI does not have to
 * stitch three reads together. {@code baseUom} is null when the SKU has no base unit of measure;
 * {@code metadata} is empty when no warehouse was given or no profile exists for it.
 */
public record SkuCardView(
        UUID id,
        String code,
        String description,
        String imageUrl,
        BaseUom baseUom,
        Map<String, Object> metadata) {

    /** The base ("EA") unit of measure carrying the single-item dimensions and weight. */
    public record BaseUom(
            String code,
            BigDecimal lengthMm,
            BigDecimal widthMm,
            BigDecimal heightMm,
            BigDecimal weightG) {
    }
}
