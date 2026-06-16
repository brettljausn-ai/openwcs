package org.openwcs.processdesigner.assist;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * Request for design-time AI task-assist (spec §7.3): a plain-language {@code description} of what the
 * task should achieve plus the data-object {@code variables} currently available to map to. The
 * response is a suggestion only — it never changes server state or runs code.
 */
public record TaskAssistRequest(
        @NotBlank String description,
        List<Variable> variables) {

    /** A data-object variable available for input/output mapping. */
    public record Variable(String name, String type) {
    }

    /** Variables, never null. */
    public List<Variable> variablesOrEmpty() {
        return variables == null ? List.of() : variables;
    }
}
