package org.openwcs.counting.client;

import java.util.Map;
import java.util.UUID;

/**
 * Write seam onto flow-orchestrator: dispatches a device task so the ASRS retrieves the count tote
 * and the move shows up on the Transport screen.
 */
public interface FlowClient {

    /**
     * Create a transport device task. Returns the created task id, or {@code null} if the call did
     * not yield one.
     */
    UUID createTransport(UUID warehouseId, String family, String command, Map<String, Object> payload,
                         UUID correlationId);
}
