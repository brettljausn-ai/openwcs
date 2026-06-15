package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.openwcs.slotting.api.DemoClearResult;
import org.openwcs.slotting.api.DemoDashboardSeedResult;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.SkuPickDailyRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo DASHBOARD seeding (build.md §4.8) for slotting. Backfills:
 *
 * <ul>
 *   <li>{@code sku_velocity} rows for ~18 SKUs with a realistic ABC skew (a few A-movers, a long C
 *       tail) so /velocity/abc shows class counts, a Pareto curve and top/bottom movers;</li>
 *   <li>{@code sku_pick_daily} buckets across the trailing 90 days for the same SKUs, with some
 *       concentrated in the last 14 days (risers) vs older (fallers);</li>
 *   <li>a handful of {@code replenishment_task} rows (some EMERGENCY/urgent, varied ages) so
 *       /replenishment/dashboard shows urgent / open / oldest-age figures.</li>
 * </ul>
 *
 * <p>Gated to demo mode: a no-op-with-error (409) unless master-data demo mode is ON. Strictly
 * demo-only. {@code created_at} on replenishment tasks is Hibernate-managed (@CreationTimestamp), so
 * task ages are backdated with native UPDATEs.
 */
@Service
public class DemoDashboardSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoDashboardSeedService.class);

    private static final int SKU_COUNT = 18;
    private static final long RNG_SEED = 20260615L;

    private final SkuVelocityRepository velocity;
    private final SkuPickDailyRepository dailyPicks;
    private final ReplenishmentTaskRepository tasks;
    private final MasterDataClient masterData;
    private final JdbcTemplate jdbc;

    public DemoDashboardSeedService(SkuVelocityRepository velocity, SkuPickDailyRepository dailyPicks,
            ReplenishmentTaskRepository tasks, MasterDataClient masterData, JdbcTemplate jdbc) {
        this.velocity = velocity;
        this.dailyPicks = dailyPicks;
        this.tasks = tasks;
        this.masterData = masterData;
        this.jdbc = jdbc;
    }

    /**
     * Backfill ABC + replenishment dashboard data for a warehouse.
     *
     * @throws IllegalStateException if demo mode is not enabled
     */
    @Transactional
    public DemoDashboardSeedResult seed(UUID warehouseId) {
        if (!masterData.demoEnabled()) {
            throw new IllegalStateException("Demo mode is not enabled (slotting dashboard seed is demo-only).");
        }
        Random rnd = new Random(RNG_SEED);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<UUID> skus = new ArrayList<>();
        for (int i = 0; i < SKU_COUNT; i++) {
            skus.add(UUID.randomUUID());
        }

        int velocityRows = 0;
        int pickDays = 0;

        for (int i = 0; i < SKU_COUNT; i++) {
            UUID sku = skus.get(i);
            // ABC skew: ~top 20% A, next 30% B, rest C, with a steep score decline (Pareto-ish).
            String cls = i < SKU_COUNT * 0.2 ? "A" : (i < SKU_COUNT * 0.5 ? "B" : "C");
            double base = 100.0 / (i + 1); // long C tail
            SkuVelocity v = new SkuVelocity();
            v.setWarehouseId(warehouseId);
            v.setSkuId(sku);
            v.setScore(BigDecimal.valueOf(Math.round(base * 100.0) / 100.0));
            v.setPendingPicks(BigDecimal.ZERO);
            v.setVelocityClass(cls);
            v.setLastPickAt(Instant.now().minus(rnd.nextInt(10), ChronoUnit.DAYS));
            v.setDecayedAt(Instant.now());
            velocity.save(v);
            velocityRows++;

            // Daily pick tallies over 90 days. Profile by index:
            //   risers (every 5th): picks concentrated in the last 14 days, little before.
            //   fallers (every 5th, offset): picks concentrated 30-80 days ago, little recently.
            //   the rest: steady spread.
            boolean riser = i % 5 == 0;
            boolean faller = i % 5 == 2;
            int daysWithPicks = Math.max(3, (int) Math.round(base / 8.0));
            for (int d = 0; d < daysWithPicks; d++) {
                int dayAgo;
                if (riser) {
                    dayAgo = rnd.nextInt(14);
                } else if (faller) {
                    dayAgo = 30 + rnd.nextInt(50);
                } else {
                    dayAgo = rnd.nextInt(90);
                }
                long picks = 1 + rnd.nextInt(Math.max(1, (int) Math.round(base / 4.0)));
                dailyPicks.addPicks(warehouseId, sku, today.minusDays(dayAgo), picks);
                pickDays++;
            }
        }

        // Replenishment tasks: a few EMERGENCY (urgent) plus SCHEDULED, varied ages so oldest-age
        // and urgent counts are non-zero on the dashboard.
        int taskCount = 0;
        String[][] specs = {
            {"EMERGENCY", "240"}, {"EMERGENCY", "75"}, {"SCHEDULED", "180"},
            {"SCHEDULED", "30"}, {"SCHEDULED", "12"}, {"OPPORTUNISTIC", "5"},
        };
        for (String[] spec : specs) {
            ReplenishmentTask t = new ReplenishmentTask();
            t.setWarehouseId(warehouseId);
            t.setSkuId(skus.get(rnd.nextInt(skus.size())));
            t.setUomId(UUID.randomUUID());
            t.setToLocationId(UUID.randomUUID());
            t.setQty(BigDecimal.valueOf(10 + rnd.nextInt(40)));
            t.setPriority(spec[0]);
            t.setTriggerType("BELOW_MIN");
            t.setStatus("PLANNED");
            ReplenishmentTask saved = tasks.save(t);
            Instant created = Instant.now().minus(Long.parseLong(spec[1]), ChronoUnit.MINUTES);
            jdbc.update("update slotting.replenishment_task set created_at = ? where replenishment_task_id = ?",
                    Timestamp.from(created), saved.getId());
            taskCount++;
        }

        log.info("demo dashboard seed for warehouse {}: backfilled {} velocity rows, {} pick-day buckets"
                        + " and {} replenishment tasks because an admin requested slotting dashboard demo data",
                warehouseId, velocityRows, pickDays, taskCount);
        return new DemoDashboardSeedResult(velocityRows, pickDays, taskCount);
    }

    /**
     * Tear down the slotting data the dashboard seeder backfilled, invoked when demo mode is turned
     * off. The standard operational reset does not touch these tables (velocity, daily picks and the
     * open replenishment tasks are derived from real pick events that never run in demo), so they are
     * cleared here: every {@code sku_velocity} and {@code sku_pick_daily} row for the warehouse, and
     * the open (PLANNED) {@code replenishment_task} rows. Block policies and other configuration are
     * untouched. Bulk DELETEs only; not demo-gated so an off-toggle always completes the teardown.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        int velocityRows = velocity.deleteBulkByWarehouseId(warehouseId);
        int pickDayRows = dailyPicks.deleteBulkByWarehouseId(warehouseId);
        int taskRows = tasks.deletePlannedByWarehouseId(warehouseId);

        log.info("demo clear for warehouse {}: removed {} velocity rows, {} pick-day buckets and {} open"
                        + " replenishment tasks because demo mode was switched off",
                warehouseId, velocityRows, pickDayRows, taskRows);
        return new DemoClearResult(velocityRows, pickDayRows, taskRows);
    }
}
