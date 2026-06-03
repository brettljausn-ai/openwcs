package org.openwcs.process.client;

import java.util.Map;
import java.util.UUID;

/** Port a BPMN service task uses to originate a device task in flow-orchestrator. */
public interface DeviceTaskClient {
    void dispatch(UUID warehouseId, String family, UUID equipmentId, String command, Map<String, Object> payload,
                  UUID correlationId);
}
