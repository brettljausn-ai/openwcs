package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Result of a task-step checkpoint: the merged data object and whether the instance is done. */
public record CheckpointResult(
        JsonNode data,
        String currentStep,
        boolean done) {
}
