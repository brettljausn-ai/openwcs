package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * The instance state returned on start / resume: the full pinned definition JSON (so the client can
 * walk the screen flow), the current data object, the current step, the status, and who the run is
 * assigned to (for resume-by-user).
 */
public record InstanceView(
        UUID instanceId,
        String processKey,
        int defVersion,
        String status,
        JsonNode def,
        JsonNode data,
        String currentStep,
        String assignedTo) {
}
