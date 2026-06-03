package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.slotting.domain.SkuVelocity;

/**
 * Read model for a SKU's current recency-weighted velocity: the decayed EWMA score, the last
 * classified A/B/C label, plus any picks counted but not yet folded into the score.
 */
public record VelocityView(
        UUID warehouseId,
        UUID skuId,
        BigDecimal score,
        BigDecimal pendingPicks,
        String velocityClass,
        Instant lastPickAt,
        Instant decayedAt) {

    public static VelocityView of(SkuVelocity v) {
        return new VelocityView(
                v.getWarehouseId(),
                v.getSkuId(),
                v.getScore(),
                v.getPendingPicks(),
                v.getVelocityClass(),
                v.getLastPickAt(),
                v.getDecayedAt());
    }
}
