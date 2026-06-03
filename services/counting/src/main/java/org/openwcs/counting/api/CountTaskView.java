package org.openwcs.counting.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.counting.domain.CountTask;

/** A count task projected for the API. */
public record CountTaskView(
        UUID id,
        UUID warehouseId,
        String scopeType,
        UUID scopeRef,
        String countType,
        String origin,
        UUID scheduleId,
        UUID parentTaskId,
        BigDecimal tolerance,
        UUID gtpStationId,
        String processInstanceId,
        String status,
        String assignedTo,
        String countedBy,
        Instant countedAt,
        String reconciledBy,
        Instant reconciledAt) {

    public static CountTaskView from(CountTask t) {
        return new CountTaskView(
                t.getId(), t.getWarehouseId(), t.getScopeType(), t.getScopeRef(), t.getCountType(),
                t.getOrigin(), t.getScheduleId(), t.getParentTaskId(), t.getTolerance(), t.getGtpStationId(),
                t.getProcessInstanceId(), t.getStatus(), t.getAssignedTo(), t.getCountedBy(), t.getCountedAt(),
                t.getReconciledBy(), t.getReconciledAt());
    }
}
