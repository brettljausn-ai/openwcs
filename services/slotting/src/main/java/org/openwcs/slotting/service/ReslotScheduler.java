package org.openwcs.slotting.service;

import org.openwcs.slotting.domain.BlockPolicy;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Off-peak re-slotting: on the configured cron ({@code openwcs.slotting.offpeak-cron}) every
 * re-slot-enabled block is re-evaluated and move recommendations are generated. Cron-only, so it
 * never fires during tests with the default schedule.
 */
@Component
public class ReslotScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReslotScheduler.class);

    private final ReslotService reslot;
    private final BlockPolicyRepository policies;

    public ReslotScheduler(ReslotService reslot, BlockPolicyRepository policies) {
        this.reslot = reslot;
        this.policies = policies;
    }

    @Scheduled(cron = "${openwcs.slotting.offpeak-cron:0 0 2 * * *}")
    @SchedulerLock(name = "slotting-reslot")
    public void reslotEnabledBlocks() {
        for (BlockPolicy p : policies.findByReslotEnabledTrue()) {
            int created = reslot.recommendForBlock(p.getWarehouseId(), p.getBlockId()).size();
            if (created > 0) {
                log.info("off-peak re-slot recommended {} moves for block {}", created, p.getBlockId());
            }
        }
    }
}
