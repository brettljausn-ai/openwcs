package org.openwcs.allocation.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * One pick that fulfils part of an order line: a quantity reserved at a specific pick
 * location, with the pick-type UoM breakdown (e.g. {@code {"CASE":2,"EACH":6}}).
 * Persisted as JSONB on {@code allocation_line.picks}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Pick(
        UUID locationId,
        BigDecimal qty,
        UUID reservationId,
        Map<String, Integer> uomBreakdown) {
}
