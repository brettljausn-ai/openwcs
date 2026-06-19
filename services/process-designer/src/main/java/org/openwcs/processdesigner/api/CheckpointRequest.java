package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * Post a task-step checkpoint: the step id (which must be a task step) and the current data object
 * captured by the client across the preceding screen steps. Merged into the instance, then the
 * task runs and its outputs merge back. Idempotent on (instanceId, stepId). The optional {@code seq}
 * positions the resulting step-history event; if omitted the server appends it after the instance's
 * current highest seq.
 */
public record CheckpointRequest(
        @NotBlank String stepId,
        JsonNode data,
        Long seq) {
}
