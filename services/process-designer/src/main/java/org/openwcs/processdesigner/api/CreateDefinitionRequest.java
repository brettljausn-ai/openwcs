package org.openwcs.processdesigner.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * Create a new DRAFT definition. The version is auto-assigned (max existing version for the key + 1).
 * {@code dataSchema}/{@code steps}/{@code start} are optional fragments of the model; whatever is
 * provided is stored in the draft JSON (the designer fills the rest in via {@code PUT}).
 */
public record CreateDefinitionRequest(
        @NotBlank String processKey,
        @NotBlank String title,
        String icon,
        JsonNode dataSchema,
        JsonNode steps,
        String start) {
}
