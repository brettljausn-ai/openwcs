package org.openwcs.slotting.velocity;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-taught, recency-weighted ABC classifier. On recompute it (1) decays each SKU's stored
 * EWMA score toward zero by {@code 2^(-Δt_days / halfLife)} for the time since it was last
 * decayed, (2) folds in the picks the consumer has tallied since then, (3) ranks SKUs in the
 * warehouse by the fresh score, and (4) assigns A/B/C by configurable rank shares — writing the
 * class onto every matching {@code storage_profile} unless {@code manual_override} is set.
 *
 * <p>Because recent picks dominate the EWMA, a SKU that spikes ramps to A within a recompute or
 * two, and one that goes quiet decays back to B/C — exactly the "new-season item starts A, fades"
 * behaviour the slotting engine wants, rather than a flat long-window average.
 */
@Service
public class VelocityClassifier {

    private static final Logger log = LoggerFactory.getLogger(VelocityClassifier.class);

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final double SECONDS_PER_DAY = 86_400d;

    private final SkuVelocityRepository velocity;
    private final StorageProfileRepository profiles;
    private final BlockPolicyRepository policies;

    public VelocityClassifier(SkuVelocityRepository velocity,
                              StorageProfileRepository profiles,
                              BlockPolicyRepository policies) {
        this.velocity = velocity;
        this.profiles = profiles;
        this.policies = policies;
    }

    /** Recompute scores + classes for a warehouse. Returns the freshly ranked velocity rows. */
    @Transactional
    public List<SkuVelocity> recompute(UUID warehouseId) {
        VelocityConfig cfg = resolveConfig(warehouseId);
        Instant now = Instant.now();

        List<SkuVelocity> rows = velocity.findByWarehouseId(warehouseId);
        for (SkuVelocity row : rows) {
            decayAndFold(row, cfg.halfLifeDays(), now);
        }

        // Rank by fresh decayed score, highest first; ties broken by most-recent pick.
        rows.sort(Comparator
                .comparing(SkuVelocity::getScore, Comparator.reverseOrder())
                .thenComparing(r -> r.getLastPickAt() == null ? Instant.EPOCH : r.getLastPickAt(),
                        Comparator.reverseOrder()));

        assignClasses(rows, cfg);
        velocity.saveAll(rows);
        applyToProfiles(warehouseId, rows);

        log.info("velocity recompute for warehouse {}: {} SKUs ranked (halfLife={}d, A={}, B={})",
                warehouseId, rows.size(), cfg.halfLifeDays(), cfg.aShare(), cfg.bShare());
        return rows;
    }

    /**
     * Decay the stored score for elapsed time, then fold in pending picks. The decay factor is
     * {@code 2^(-Δt/halfLife)}: after one half-life the prior score is halved. With no half-life
     * (≤0) decay is skipped (the score becomes a plain running count).
     */
    private void decayAndFold(SkuVelocity row, BigDecimal halfLifeDays, Instant now) {
        Instant from = row.getDecayedAt();
        BigDecimal decayed = row.getScore();
        if (from != null && halfLifeDays != null && halfLifeDays.signum() > 0) {
            double elapsedDays = Math.max(0d, Duration.between(from, now).toMillis() / 1000d / SECONDS_PER_DAY);
            double factor = Math.pow(2.0, -elapsedDays / halfLifeDays.doubleValue());
            decayed = decayed.multiply(BigDecimal.valueOf(factor), MC);
        }
        BigDecimal folded = decayed.add(row.getPendingPicks(), MC);
        row.setScore(folded.setScale(6, RoundingMode.HALF_UP));
        row.setPendingPicks(BigDecimal.ZERO);
        row.setDecayedAt(now);
    }

    /**
     * Assign A/B/C by rank share. With {@code n} SKUs the top {@code ceil(n*aShare)} become A and
     * the next {@code ceil(n*bShare)} become B; the rest are C. SKUs with a zero score never rank
     * above C (no observed velocity = slow mover).
     */
    private void assignClasses(List<SkuVelocity> ranked, VelocityConfig cfg) {
        int n = ranked.size();
        if (n == 0) {
            return;
        }
        int aCount = shareCount(n, cfg.aShare());
        int bCount = shareCount(n, cfg.bShare());
        for (int i = 0; i < n; i++) {
            SkuVelocity row = ranked.get(i);
            String cls;
            if (row.getScore().signum() <= 0) {
                cls = "C";
            } else if (i < aCount) {
                cls = "A";
            } else if (i < aCount + bCount) {
                cls = "B";
            } else {
                cls = "C";
            }
            row.setVelocityClass(cls);
        }
    }

    private static int shareCount(int n, BigDecimal share) {
        if (share == null || share.signum() <= 0) {
            return 0;
        }
        return (int) Math.ceil(n * share.doubleValue());
    }

    /** Push the learned class onto every matching storage_profile, skipping manual overrides. */
    private void applyToProfiles(UUID warehouseId, List<SkuVelocity> rows) {
        for (SkuVelocity row : rows) {
            if (row.getVelocityClass() == null) {
                continue;
            }
            List<StorageProfile> matches = profiles.findByWarehouseIdAndSkuId(warehouseId, row.getSkuId());
            for (StorageProfile profile : matches) {
                if (profile.isManualOverride()) {
                    log.debug("sku {} keeps manual velocity class {} in block {} (manual override; learned {})",
                            row.getSkuId(), profile.getVelocityClass(), profile.getBlockId(),
                            row.getVelocityClass());
                    continue;
                }
                if (!row.getVelocityClass().equals(profile.getVelocityClass())) {
                    log.info("sku {} velocity reclassified {} -> {} in block {} (decayed EWMA score {})",
                            row.getSkuId(), profile.getVelocityClass(), row.getVelocityClass(),
                            profile.getBlockId(), row.getScore());
                    profile.setVelocityClass(row.getVelocityClass());
                    profiles.save(profile);
                }
            }
        }
    }

    /** First block policy for the warehouse supplies the knobs; defaults otherwise. */
    private VelocityConfig resolveConfig(UUID warehouseId) {
        List<BlockPolicy> warehousePolicies = new ArrayList<>(policies.findByWarehouseId(warehouseId));
        return warehousePolicies.isEmpty()
                ? VelocityConfig.defaults()
                : VelocityConfig.from(warehousePolicies.get(0));
    }
}
