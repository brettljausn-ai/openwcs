package org.openwcs.processdesigner.api;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Start a new RUNNING instance of a process key's ACTIVE definition. */
public record StartInstanceRequest(
        @NotBlank String processKey,
        UUID warehouseId) {
}
