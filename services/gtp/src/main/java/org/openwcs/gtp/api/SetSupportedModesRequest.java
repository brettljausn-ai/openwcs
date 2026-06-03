package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Configure the set of operating modes a station supports (PICKING | DECANTING | STOCK_COUNT | QC |
 * MAINTENANCE). Replaces the station's current supported-modes set.
 */
public record SetSupportedModesRequest(
        @NotEmpty List<String> supportedModes) {
}
