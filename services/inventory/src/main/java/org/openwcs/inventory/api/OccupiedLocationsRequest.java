package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;

/** Request body for the per-location occupancy check: the set of location ids to test. */
public record OccupiedLocationsRequest(List<UUID> locationIds) {
}
