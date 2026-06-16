package org.openwcs.processdesigner.assist;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The AI task-assist suggestion (spec §7.3) — a DESIGN-TIME suggestion the designer reviews and may
 * apply; the endpoint never saves it, never changes server state, and never runs code. The model
 * either:
 * <ul>
 *   <li>{@code kind = "curated"}: picks a curated task type and proposes input/output mappings
 *       ({@code taskType}, {@code inputs} = taskInput → variable-name-or-literal, {@code outputs} =
 *       taskOutput → variable-name);</li>
 *   <li>{@code kind = "script"}: drafts a sandboxed JS snippet ({@code script}) for the user to review
 *       and insert into a script step — a DRAFT only, never executed by this endpoint;</li>
 *   <li>{@code kind = "none"}: nothing fits.</li>
 * </ul>
 * {@code rationale} explains the choice (and, for a script, that it is an unreviewed draft);
 * {@code confidence} is 0..1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskAssistResult(
        String kind,
        String taskType,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        String script,
        String rationale,
        double confidence) {

    public static TaskAssistResult none(String rationale) {
        return new TaskAssistResult("none", null, null, null, null, rationale, 0.0);
    }
}
