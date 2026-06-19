package org.openwcs.slotting.api;

/** Result of a release: how many reservations were freed (0 when there was nothing to free). */
public record ReleaseResult(int freed) {
}
