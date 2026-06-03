package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Add a single node to an existing station. */
public record AddNodeRequest(
        @NotBlank String role,
        @NotBlank String code,
        String putLightId,
        UUID locationId,
        UUID orderHuId,
        Integer position) {
}
