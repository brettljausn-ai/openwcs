package org.openwcs.process.client;

import java.util.UUID;

/** Port a BPMN service task uses to ask the slotting service where to put away an HU. */
public interface SlottingClient {

    Putaway assignPutaway(UUID warehouseId, UUID huId, UUID skuId, UUID batchId, UUID uomId,
                          Object qty, UUID blockId);

    record Putaway(UUID locationId, UUID blockId, String mode) {
    }
}
