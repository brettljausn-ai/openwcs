package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.api.AbcMoversView;
import org.openwcs.slotting.api.AbcMoversView.Mover;
import org.openwcs.slotting.api.AbcMoversView.ParetoEntry;
import org.openwcs.slotting.api.AbcMoversView.Trend;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the read-only ABC / movers dashboard from the learned velocity snapshot.
 *
 * <p><b>Data honesty.</b> The velocity store keeps only a decayed EWMA pick-frequency score per SKU
 * ({@link SkuVelocity#getScore()}) plus the picks counted since the last decay
 * ({@link SkuVelocity#getPendingPicks()}); it does <em>not</em> persist raw, timestamped pick events
 * or periodic snapshots. True trailing-90d and trailing-14d windowed pick counts are therefore not
 * recoverable here without new persistence. Rather than fabricate windows, this service:
 * <ul>
 *   <li>uses the decayed EWMA {@code score} as the trailing pick-frequency proxy for the Pareto
 *       curve, A/B/C counts, and top/bottom movers (the score already favours recent activity with
 *       the configured ~90d-scale half-life), and</li>
 *   <li>approximates risers/fallers as a <em>short</em> window (the un-folded {@code pendingPicks}
 *       counted most recently) versus a <em>long</em> window (the established decayed {@code score}),
 *       each expressed as a percent of the warehouse's total activity in that window. A SKU whose
 *       recent share exceeds its established share is a riser; the reverse is a faller.</li>
 * </ul>
 * This is documented as an approximation; replacing it with exact windows needs a windowed pick
 * count (persisted snapshots or queryable raw events), which is out of scope for this read endpoint.
 */
@Service
public class VelocityDashboardService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int MOVERS_LIMIT = 10;

    private final SkuVelocityRepository velocity;

    public VelocityDashboardService(SkuVelocityRepository velocity) {
        this.velocity = velocity;
    }

    @Transactional(readOnly = true)
    public AbcMoversView build(UUID warehouseId) {
        List<SkuVelocity> rows = velocity.findByWarehouseId(warehouseId);

        long a = rows.stream().filter(r -> "A".equals(r.getVelocityClass())).count();
        long b = rows.stream().filter(r -> "B".equals(r.getVelocityClass())).count();
        long c = rows.stream().filter(r -> "C".equals(r.getVelocityClass())).count();

        // Rank by the decayed pick-frequency proxy, highest first; ties broken by most-recent pick.
        List<SkuVelocity> ranked = new ArrayList<>(rows);
        ranked.sort(Comparator
                .comparing((SkuVelocity r) -> score(r), Comparator.reverseOrder())
                .thenComparing(r -> r.getLastPickAt() == null ? java.time.Instant.EPOCH : r.getLastPickAt(),
                        Comparator.reverseOrder()));

        BigDecimal total = ranked.stream().map(this::score).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ParetoEntry> pareto = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (SkuVelocity r : ranked) {
            running = running.add(score(r));
            BigDecimal cumPct = total.signum() == 0
                    ? BigDecimal.ZERO
                    : running.multiply(BigDecimal.valueOf(100), MC).divide(total, 2, RoundingMode.HALF_UP);
            pareto.add(new ParetoEntry(r.getSkuId(), score(r).setScale(2, RoundingMode.HALF_UP), cumPct));
        }

        List<Mover> top = ranked.stream()
                .limit(MOVERS_LIMIT)
                .map(r -> new Mover(r.getSkuId(), score(r).setScale(2, RoundingMode.HALF_UP)))
                .toList();

        // Bottom = slowest movers that still showed velocity (score > 0), slowest first.
        List<SkuVelocity> withVelocity = ranked.stream().filter(r -> score(r).signum() > 0).toList();
        List<Mover> bottom = withVelocity.stream()
                .sorted(Comparator.comparing(this::score))
                .limit(MOVERS_LIMIT)
                .map(r -> new Mover(r.getSkuId(), score(r).setScale(2, RoundingMode.HALF_UP)))
                .toList();

        // Trend proxy: short window = recent un-folded picks; long window = established decayed score.
        BigDecimal shortTotal = ranked.stream().map(SkuVelocity::getPendingPicks)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal longTotal = ranked.stream().map(SkuVelocity::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Trend> trends = new ArrayList<>();
        for (SkuVelocity r : ranked) {
            BigDecimal pct14d = pct(r.getPendingPicks(), shortTotal);
            BigDecimal pct90d = pct(r.getScore(), longTotal);
            trends.add(new Trend(r.getSkuId(), pct14d, pct90d));
        }

        List<Trend> risers = trends.stream()
                .filter(t -> t.pct14d().compareTo(t.pct90d()) > 0)
                .sorted(Comparator.comparing((Trend t) -> t.pct14d().subtract(t.pct90d())).reversed())
                .limit(MOVERS_LIMIT)
                .toList();
        List<Trend> fallers = trends.stream()
                .filter(t -> t.pct14d().compareTo(t.pct90d()) < 0)
                .sorted(Comparator.comparing((Trend t) -> t.pct14d().subtract(t.pct90d())))
                .limit(MOVERS_LIMIT)
                .toList();

        return new AbcMoversView(a, b, c, pareto, top, bottom, risers, fallers);
    }

    /** Pick-frequency proxy: the decayed EWMA score plus any picks counted but not yet folded in. */
    private BigDecimal score(SkuVelocity r) {
        return r.getScore().add(r.getPendingPicks(), MC);
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100), MC).divide(total, 2, RoundingMode.HALF_UP);
    }
}
