package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;

/** Request body for the occupancy check: the set of location ids to test for stock / handling units. */
public record OccupancyRequest(List<UUID> locationIds) {
}
