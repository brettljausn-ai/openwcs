package org.openwcs.inventory.api;

import java.util.UUID;

/**
 * Body of {@code PUT /api/inventory/handling-units/{id}/location} — the controlled location-booking
 * path through the transport lifecycle. {@code null} means the HU left its slot (in transit / at a
 * workplace; the HU transport trace is the truth while away).
 */
public record LocationUpdateRequest(UUID locationId) {
}
