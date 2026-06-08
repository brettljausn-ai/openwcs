package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;

/** The subset of the requested locations that currently hold any stock row or handling unit. */
public record OccupiedLocationsResult(List<UUID> occupiedLocationIds) {
}
