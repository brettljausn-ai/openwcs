package org.openwcs.gtp.api;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Configure a station's in-transit handling-unit caps: the maximum number of HUs that may have an
 * active transport inbound to the station at once, split by mode class (PICKING vs OTHER). Replaces
 * the station's current caps. Both values must be non-negative.
 */
public record SetCapacityRequest(
        @PositiveOrZero int maxInTransitPicking,
        @PositiveOrZero int maxInTransitOther) {
}
