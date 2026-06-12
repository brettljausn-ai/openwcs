package org.openwcs.flow.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled retry for return legs whose tote is circulating on the conveyor because slotting had no
 * answer at completion time (awaiting-slot entries): every ~30s it re-asks slotting and, on an
 * answer, {@link InductionQueueService#retryAwaitingSlots} assigns the destination + route plan so
 * the tote adapts at its next scan (or the STORE fires directly when the plan-less CONVEY already
 * completed). ShedLock guards the sweep so it runs on a single replica when scaled out.
 */
@Component
public class InductionSlotSweeper {

    private final InductionQueueService induction;

    public InductionSlotSweeper(InductionQueueService induction) {
        this.induction = induction;
    }

    @Scheduled(fixedDelayString = "${openwcs.flow.slot-retry-millis:30000}",
            initialDelayString = "${openwcs.flow.slot-retry-millis:30000}")
    @SchedulerLock(name = "flow-induction-slot-retry", lockAtMostFor = "PT5M")
    public void retry() {
        induction.retryAwaitingSlots("slot-retry-sweep");
    }
}
