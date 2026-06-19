package org.openwcs.slotting.api;

import java.util.UUID;

/**
 * Body for {@code POST /api/slotting/replenishment/claim-next}: the operator (taken from
 * {@code X-Auth-User}) claims the next eligible collection task in {@code areaId} for
 * {@code warehouseId}, reserving it to the runtime {@code instanceId} (so it can be released later).
 */
public record ClaimNextRequest(UUID warehouseId, UUID areaId, String instanceId) {
}
