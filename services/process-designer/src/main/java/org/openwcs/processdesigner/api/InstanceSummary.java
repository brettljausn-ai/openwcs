package org.openwcs.processdesigner.api;

import java.time.Instant;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessInstance;

/**
 * A lightweight instance summary (no data object / no definition body) for the monitoring list
 * (spec §14 instance history/monitoring). Newest-first.
 */
public record InstanceSummary(
        UUID instanceId,
        String processKey,
        int defVersion,
        String status,
        String currentStep,
        String startedBy,
        UUID warehouseId,
        Instant startedAt,
        Instant updatedAt) {

    public static InstanceSummary from(ProcessInstance i) {
        return new InstanceSummary(i.getId(), i.getProcessKey(), i.getDefVersion(), i.getStatus(),
                i.getCurrentStep(), i.getStartedBy(), i.getWarehouseId(), i.getStartedAt(), i.getUpdatedAt());
    }
}
