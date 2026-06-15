package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.api.ReplenishmentDashboardView;
import org.openwcs.slotting.api.ReplenishmentDashboardView.ProjectedStockout;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.openwcs.slotting.velocity.VelocityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the read-only replenishment health dashboard for one warehouse.
 *
 * <p>{@code urgent}, {@code openTasks}, and {@code oldestAgeMin} come straight from the open
 * (PLANNED) replenishment task store. {@code projectedStockouts} is <b>best-effort</b>: the velocity
 * store keeps a recency-weighted EWMA pick-<em>frequency</em> score (one count per outbound pick,
 * over the configured half-life), not a per-time qty consumption rate, so an exact qty-based runway
 * is not cleanly derivable. We approximate picks/min as {@code score / (halfLifeDays * 1440)} and,
 * treating one pick as roughly one unit consumed, project {@code minutesToStockout = onHand /
 * picksPerMin} for faces with a positive velocity and a known on-hand. Faces with no observed
 * velocity, no on-hand, or where inventory is unavailable are skipped (so the list may be empty —
 * documented in the OpenAPI contract).
 */
@Service
public class ReplenishmentDashboardService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentDashboardService.class);

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String PLANNED = "PLANNED";
    private static final String EMERGENCY = "EMERGENCY";
    private static final BigDecimal MINUTES_PER_DAY = BigDecimal.valueOf(1440);
    private static final int STOCKOUT_LIMIT = 20;

    private final ReplenishmentTaskRepository tasks;
    private final PickSlotRepository pickSlots;
    private final SkuVelocityRepository velocity;
    private final InventoryClient inventory;

    public ReplenishmentDashboardService(ReplenishmentTaskRepository tasks,
                                         PickSlotRepository pickSlots,
                                         SkuVelocityRepository velocity,
                                         InventoryClient inventory) {
        this.tasks = tasks;
        this.pickSlots = pickSlots;
        this.velocity = velocity;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public ReplenishmentDashboardView build(UUID warehouseId) {
        List<ReplenishmentTask> open = tasks.findByWarehouseIdAndStatus(warehouseId, PLANNED);

        long urgent = open.stream().filter(t -> EMERGENCY.equals(t.getPriority())).count();
        long openTasks = open.size();

        Instant now = Instant.now();
        long oldestAgeMin = open.stream()
                .map(ReplenishmentTask::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .map(created -> Math.max(0, Duration.between(created, now).toMinutes()))
                .max(Comparator.naturalOrder())
                .orElse(0L);

        List<ProjectedStockout> projected = projectStockouts(warehouseId);

        return new ReplenishmentDashboardView(urgent, openTasks, oldestAgeMin, projected);
    }

    private List<ProjectedStockout> projectStockouts(UUID warehouseId) {
        List<ProjectedStockout> out = new ArrayList<>();
        // Default half-life (the dashboard reads the same scale the classifier uses).
        BigDecimal halfLifeDays = VelocityConfig.DEFAULT_HALF_LIFE_DAYS;
        for (PickSlot slot : pickSlots.findByWarehouseIdAndStatus(warehouseId, "ACTIVE")) {
            SkuVelocity vel = velocity.findByWarehouseIdAndSkuId(warehouseId, slot.getSkuId()).orElse(null);
            if (vel == null) {
                continue;
            }
            BigDecimal rate = vel.getScore().add(vel.getPendingPicks(), MC); // picks over the EWMA window
            if (rate.signum() <= 0) {
                continue;
            }
            BigDecimal onHand;
            try {
                onHand = inventory.onHandAtLocation(warehouseId, slot.getSkuId(), slot.getLocationId());
            } catch (RuntimeException e) {
                log.debug("inventory unavailable for stockout projection at {}: {}",
                        slot.getLocationId(), e.toString());
                continue;
            }
            if (onHand == null || onHand.signum() <= 0) {
                continue;
            }
            BigDecimal picksPerMin = rate.divide(halfLifeDays.multiply(MINUTES_PER_DAY, MC), MC);
            if (picksPerMin.signum() <= 0) {
                continue;
            }
            long minutes = onHand.divide(picksPerMin, 0, RoundingMode.HALF_UP).longValueExact();
            out.add(new ProjectedStockout(slot.getLocationId().toString(), minutes));
        }
        out.sort(Comparator.comparingLong(ProjectedStockout::minutesToStockout));
        return out.size() > STOCKOUT_LIMIT ? out.subList(0, STOCKOUT_LIMIT) : out;
    }
}
