package org.openwcs.slotting.service;

import java.util.UUID;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opportunistic, off-peak top-off: on the configured cron ({@code openwcs.slotting.offpeak-cron})
 * every warehouse's pick faces are refilled toward max while picking is idle. Emergency below-min
 * replenishment is event/API-driven (see {@link ReplenishmentService#planBelowMin}); this job is
 * cron-only so it never fires during tests with the default schedule.
 */
@Component
public class ReplenishmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentScheduler.class);

    private final ReplenishmentService replenishment;
    private final PickSlotRepository pickSlots;

    public ReplenishmentScheduler(ReplenishmentService replenishment, PickSlotRepository pickSlots) {
        this.replenishment = replenishment;
        this.pickSlots = pickSlots;
    }

    @Scheduled(cron = "${openwcs.slotting.offpeak-cron:0 0 2 * * *}")
    public void topOffAllWarehouses() {
        for (UUID warehouseId : pickSlots.findDistinctActiveWarehouseIds()) {
            int created = replenishment.topOff(warehouseId).size();
            if (created > 0) {
                log.info("off-peak top-off created {} replenishment tasks for warehouse {}", created, warehouseId);
            }
        }
    }
}
