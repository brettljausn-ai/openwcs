package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.slotting.api.AbcMoversView;
import org.openwcs.slotting.api.AbcMoversView.Mover;
import org.openwcs.slotting.api.AbcMoversView.ParetoEntry;
import org.openwcs.slotting.api.AbcMoversView.Trend;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.SkuPickDailyRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the read-only ABC / movers dashboard from the learned velocity snapshot.
 *
 * <p>Class counts, the Pareto curve, and top/bottom movers rank SKUs by the decayed EWMA
 * pick-frequency score ({@link SkuVelocity#getScore()} plus any not-yet-folded
 * {@link SkuVelocity#getPendingPicks()}) — the same score the classifier ranks on, so the
 * dashboard agrees with the assigned A/B/C labels.
 *
 * <p>Risers/fallers use <em>exact</em> trailing windows summed from {@code sku_pick_daily}
 * (one pick bucket per calendar day): {@code pct14d} is a SKU's last-14-day picks as a percent of
 * the warehouse's 14-day total, {@code pct90d} the same over 90 days. A SKU whose recent share
 * materially exceeds its established share is a riser (sorted by delta descending); the inverse is
 * a faller. These are real windowed counts, not an approximation.
 */
@Service
public class VelocityDashboardService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final int MOVERS_LIMIT = 10;

    /** Trailing windows for the riser/faller comparison (inclusive of the current day). */
    static final int SHORT_WINDOW_DAYS = 14;
    static final int LONG_WINDOW_DAYS = 90;

    private final SkuVelocityRepository velocity;
    private final SkuPickDailyRepository dailyPicks;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public VelocityDashboardService(SkuVelocityRepository velocity, SkuPickDailyRepository dailyPicks) {
        this(velocity, dailyPicks, Clock.systemUTC());
    }

    VelocityDashboardService(SkuVelocityRepository velocity, SkuPickDailyRepository dailyPicks, Clock clock) {
        this.velocity = velocity;
        this.dailyPicks = dailyPicks;
        this.clock = clock;
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

        // Exact trailing windows from the daily pick tallies (sku_pick_daily, migration V6).
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from14 = today.minusDays(SHORT_WINDOW_DAYS - 1L);
        LocalDate from90 = today.minusDays(LONG_WINDOW_DAYS - 1L);

        Map<UUID, Long> picks14 = dailyPicks.windowPicksBySku(warehouseId, from14, today);
        Map<UUID, Long> picks90 = dailyPicks.windowPicksBySku(warehouseId, from90, today);
        long total14 = dailyPicks.windowTotal(warehouseId, from14, today);
        long total90 = dailyPicks.windowTotal(warehouseId, from90, today);

        // Any SKU active in either window is a trend candidate.
        java.util.Set<UUID> skus = new java.util.LinkedHashSet<>();
        skus.addAll(picks90.keySet());
        skus.addAll(picks14.keySet());

        List<Trend> trends = new ArrayList<>();
        for (UUID sku : skus) {
            BigDecimal pct14d = pct(picks14.getOrDefault(sku, 0L), total14);
            BigDecimal pct90d = pct(picks90.getOrDefault(sku, 0L), total90);
            trends.add(new Trend(sku, pct14d, pct90d));
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

    private static BigDecimal pct(long part, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100), MC)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}
