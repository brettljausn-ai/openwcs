package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Update an existing station node's configuration: its {@code role} (STOCK | ORDER), {@code code},
 * put-light / destination wiring ({@code putLightId}), mapped {@code locationId}, bound order HU,
 * display {@code position}, and {@code status}.
 */
public record UpdateNodeRequest(
        @NotBlank String role,
        @NotBlank String code,
        String putLightId,
        UUID locationId,
        UUID orderHuId,
        Integer position,
        String status) {
}
