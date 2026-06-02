package org.openwcs.flow.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.domain.DeviceTask;

/** Read model for a device task. */
public record DeviceTaskView(
        UUID id,
        UUID warehouseId,
        String family,
        UUID equipmentId,
        String command,
        Map<String, Object> payload,
        UUID correlationId,
        String status,
        String detail,
        Map<String, Object> result,
        String actor,
        Instant createdAt) {

    public static DeviceTaskView from(DeviceTask t) {
        return new DeviceTaskView(t.getId(), t.getWarehouseId(), t.getFamily(), t.getEquipmentId(),
                t.getCommand(), t.getPayload(), t.getCorrelationId(), t.getStatus(), t.getDetail(),
                t.getResult(), t.getActor(), t.getCreatedAt());
    }
}
