package org.openwcs.slotting.api;

/**
 * Body for {@code POST /api/slotting/replenishment/release}: free every uncommitted reservation held
 * by this runtime {@code instanceId} (e.g. the operator logged out or the handheld went away).
 */
public record ReleaseRequest(String instanceId) {
}
