package org.openwcs.slotting.api;

import java.util.UUID;

/** Dig-out planning request (ADR 0009): {@code locationId} is the retrieve's source slot. */
public record RelocationPlanRequest(UUID warehouseId, UUID locationId) {
}
