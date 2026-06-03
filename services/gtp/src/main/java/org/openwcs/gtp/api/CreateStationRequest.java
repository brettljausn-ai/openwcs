package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Configure a GTP station and (optionally) its nodes in one call. {@code mode} is the destination
 * topology (ORDER_LOCATION or PUT_WALL). {@code supportedModes} is the optional set of operating
 * modes the station can run (PICKING | DECANTING | STOCK_COUNT | QC | MAINTENANCE); when omitted it
 * defaults to {@code [PICKING]}.
 */
public record CreateStationRequest(
        @NotNull UUID warehouseId,
        @NotBlank String code,
        @NotBlank String mode,
        List<String> supportedModes,
        List<NodeSpec> nodes) {

    /** One node to create: STOCK (presentation) or ORDER (destination). */
    public record NodeSpec(
            @NotBlank String role,
            @NotBlank String code,
            String putLightId,
            UUID locationId,
            UUID orderHuId,
            Integer position) {
    }
}
