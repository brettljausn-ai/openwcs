package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/**
 * Record a single screen-step advance so the server's current step + data object stay exact for a
 * mid-flow device swap (the mobile client posts this on every SCREEN advance). Idempotent on
 * (instanceId, seq): re-posting the same seq does not re-apply. {@code stepId} is the step the
 * client just advanced TO; {@code stepType} is its type (e.g. screen); {@code data} is the data
 * object captured so far, merged into the instance.
 */
public record StepEventRequest(
        @NotNull Long seq,
        String stepId,
        String stepType,
        JsonNode data) {
}
