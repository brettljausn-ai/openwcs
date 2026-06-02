package org.openwcs.flow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/** Request to dispatch a device task to an equipment family's adapter. */
public record RequestDeviceTask(
        @NotNull UUID warehouseId,
        @NotBlank String family,
        UUID equipmentId,
        @NotBlank String command,
        Map<String, Object> payload,
        UUID correlationId) {
}
