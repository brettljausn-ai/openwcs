package org.openwcs.slotting.velocity;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.openwcs.slotting.domain.SkuVelocity;
import org.openwcs.slotting.repo.SkuVelocityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Off-peak ABC recompute: on the shared slotting cron ({@code openwcs.slotting.offpeak-cron})
 * every warehouse that has observed movement is re-classified by the {@link VelocityClassifier}.
 * Cron-only, so it never fires during tests with the default schedule.
 */
@Component
public class VelocityScheduler {

    private static final Logger log = LoggerFactory.getLogger(VelocityScheduler.class);

    private final VelocityClassifier classifier;
    private final SkuVelocityRepository velocity;

    public VelocityScheduler(VelocityClassifier classifier, SkuVelocityRepository velocity) {
        this.classifier = classifier;
        this.velocity = velocity;
    }

    @Scheduled(cron = "${openwcs.slotting.offpeak-cron:0 0 2 * * *}")
    @SchedulerLock(name = "slotting-velocity-recompute")
    public void recomputeAllWarehouses() {
        Set<UUID> warehouses = new LinkedHashSet<>();
        for (SkuVelocity row : velocity.findAll()) {
            warehouses.add(row.getWarehouseId());
        }
        for (UUID warehouseId : warehouses) {
            int ranked = classifier.recompute(warehouseId).size();
            log.info("off-peak velocity recompute: warehouse {} reclassified {} SKUs", warehouseId, ranked);
        }
    }
}
