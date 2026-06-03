package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Create an ad-hoc (or scheduled) count task over a scope. {@code cells} enumerates the
 * (location, SKU[, batch]) lines to verify; each line's expected qty is snapshotted from inventory.
 */
public record CreateCountTaskCommand(
        UUID warehouseId,
        String scopeType,
        UUID scopeRef,
        String countType,
        String origin,
        UUID scheduleId,
        UUID parentTaskId,
        BigDecimal tolerance,
        UUID gtpStationId,
        List<CountTaskScope> cells) {
}
