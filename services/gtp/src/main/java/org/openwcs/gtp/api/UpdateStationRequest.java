package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Update a GTP station's editable configuration: its {@code code}, optional display {@code name},
 * destination topology {@code mode} (ORDER_LOCATION | PUT_WALL), {@code status}, and (optionally)
 * its supported operating modes. When {@code supportedModes} is null the current set is kept; when
 * present it replaces the set (PICKING is always retained). The warehouse is immutable.
 */
public record UpdateStationRequest(
        @NotBlank String code,
        String name,
        @NotBlank String mode,
        String status,
        List<String> supportedModes) {
}
