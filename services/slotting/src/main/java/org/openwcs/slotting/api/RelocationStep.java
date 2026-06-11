package org.openwcs.slotting.api;

import java.util.UUID;

/** One dig-out move (ADR 0009): take the blocking HU out of the channel into a free same-level slot. */
public record RelocationStep(UUID huId, String huCode, UUID fromLocationId, UUID toLocationId) {
}
