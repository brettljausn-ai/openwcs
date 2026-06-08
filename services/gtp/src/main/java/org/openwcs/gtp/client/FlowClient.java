package org.openwcs.gtp.client;

import java.util.Map;
import java.util.UUID;

/**
 * Write seam onto flow-orchestrator: dispatches a device task so a transport runs and shows up on the
 * Transport screen. The gtp service uses this to store a tote back to its source storage location once
 * all of its station work is done.
 */
public interface FlowClient {

    /**
     * Create a transport device task. Returns the created task id, or {@code null} if the call did
     * not yield one.
     */
    UUID createTransport(UUID warehouseId, String family, String command, Map<String, Object> payload,
                         UUID correlationId);
}
