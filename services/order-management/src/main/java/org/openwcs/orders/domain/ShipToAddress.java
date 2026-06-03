package org.openwcs.orders.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ship-to address for an outbound order, persisted as JSONB on {@code outbound_order.ship_to}.
 * Supplied on create / host import and used to populate dispatch labels.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShipToAddress(
        String name,
        String line1,
        String line2,
        String city,
        String region,
        String postcode,
        String country,
        String contact,
        String phone) {
}
