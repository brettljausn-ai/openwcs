package org.openwcs.process.client;

import java.util.List;
import java.util.UUID;

/** Port a BPMN service task uses to assign a handling unit a conveyor route plan. */
public interface RouteClient {
    void assignRoute(UUID warehouseId, String barcode, List<String> targets);
}
