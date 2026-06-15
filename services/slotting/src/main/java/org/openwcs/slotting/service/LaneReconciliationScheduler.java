package org.openwcs.slotting.service;

import java.util.UUID;
import org.openwcs.slotting.api.LaneReconciliationReport;
import org.openwcs.slotting.repo.BlockPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Off-peak lane-depth reconciliation: on the configured cron ({@code openwcs.slotting.offpeak-cron})
 * every warehouse's multi-deep lanes are reconciled against inventory occupancy and ghost
 * assignments are corrected (see {@link LaneReconciliationService}). Cron-only, so it never fires
 * during tests with the default schedule. Best-effort: inventory hiccups degrade to an empty pass.
 */
@Component
public class LaneReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(LaneReconciliationScheduler.class);

    private final LaneReconciliationService reconciliation;
    private final BlockPolicyRepository policies;

    public LaneReconciliationScheduler(LaneReconciliationService reconciliation,
                                       BlockPolicyRepository policies) {
        this.reconciliation = reconciliation;
        this.policies = policies;
    }

    @Scheduled(cron = "${openwcs.slotting.offpeak-cron:0 0 2 * * *}")
    @SchedulerLock(name = "slotting-lane-reconciliation")
    public void reconcileAllWarehouses() {
        for (UUID warehouseId : policies.findDistinctWarehouseIds()) {
            LaneReconciliationReport report = reconciliation.reconcile(warehouseId);
            if (report.corrected() > 0 || !report.discrepancies().isEmpty()) {
                log.info("off-peak lane reconciliation for warehouse {}: checked {} lanes,"
                                + " corrected {} ghost assignments, {} lanes still drifting",
                        warehouseId, report.lanesChecked(), report.corrected(),
                        report.discrepancies().size());
            }
        }
    }
}
