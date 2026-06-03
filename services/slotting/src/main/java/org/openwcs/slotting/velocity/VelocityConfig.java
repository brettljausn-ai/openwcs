package org.openwcs.slotting.velocity;

import java.math.BigDecimal;
import org.openwcs.slotting.domain.BlockPolicy;

/**
 * Resolved recency-weighted ABC knobs for one warehouse: the EWMA half-life and the A/B rank
 * shares. Pulled from a warehouse's {@link BlockPolicy} (the slotting tuning table) when present,
 * else the documented defaults (half-life 14d, A=top 20%, B=next 30%, C=rest).
 */
public record VelocityConfig(BigDecimal halfLifeDays, BigDecimal aShare, BigDecimal bShare) {

    public static final BigDecimal DEFAULT_HALF_LIFE_DAYS = new BigDecimal("14");
    public static final BigDecimal DEFAULT_A_SHARE = new BigDecimal("0.2");
    public static final BigDecimal DEFAULT_B_SHARE = new BigDecimal("0.3");

    public static VelocityConfig defaults() {
        return new VelocityConfig(DEFAULT_HALF_LIFE_DAYS, DEFAULT_A_SHARE, DEFAULT_B_SHARE);
    }

    public static VelocityConfig from(BlockPolicy policy) {
        if (policy == null) {
            return defaults();
        }
        BigDecimal half = policy.getVelocityHalfLifeDays() != null
                ? policy.getVelocityHalfLifeDays() : DEFAULT_HALF_LIFE_DAYS;
        BigDecimal a = policy.getAbcAShare() != null ? policy.getAbcAShare() : DEFAULT_A_SHARE;
        BigDecimal b = policy.getAbcBShare() != null ? policy.getAbcBShare() : DEFAULT_B_SHARE;
        return new VelocityConfig(half, a, b);
    }
}
