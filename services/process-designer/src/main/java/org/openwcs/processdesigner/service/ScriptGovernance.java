package org.openwcs.processdesigner.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.openwcs.processdesigner.script.SandboxedScriptRunner;
import org.openwcs.processdesigner.script.ScriptExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Defense-in-depth governance for the sandboxed scripting escape hatch (spec §7.2). A definition that
 * contains a script step (a task step with {@code task == "script"}) may be saved/published ONLY when
 * BOTH:
 * <ul>
 *   <li>{@code openwcs.process-designer.scripting.enabled} is true (off by default), else 422;</li>
 *   <li>the caller holds {@link Permission#PROCESS_SCRIPT_AUTHOR}, else 403.</li>
 * </ul>
 * At publish, every script also must parse in the sandbox (parse-only, no execution); a malformed
 * snippet is a 422 validation problem. When security is disabled (dev), the permission check is
 * skipped (the RBAC filter is off too) but the scripting flag still applies.
 */
@Component
public class ScriptGovernance {

    private final boolean scriptingEnabled;
    private final boolean securityEnabled;
    private final SandboxedScriptRunner runner;

    public ScriptGovernance(
            @Value("${openwcs.process-designer.scripting.enabled:false}") boolean scriptingEnabled,
            @Value("${openwcs.security.enabled:false}") boolean securityEnabled,
            SandboxedScriptRunner runner) {
        this.scriptingEnabled = scriptingEnabled;
        this.securityEnabled = securityEnabled;
        this.runner = runner;
    }

    public boolean scriptingEnabled() {
        return scriptingEnabled;
    }

    /** Whether the caller (by forwarded roles) may author scripts. Always true when security is off. */
    public boolean canAuthorScript(String rolesHeader) {
        if (!securityEnabled) {
            return true;
        }
        return AccessControl.granted(AccessControl.parseRoles(rolesHeader), Permission.PROCESS_SCRIPT_AUTHOR);
    }

    /**
     * Enforce the save-time gates for a definition body: if it has any script step, require the flag
     * (422) and the permission (403). Does NOT validate parsing (that is publish-time, see
     * {@link #parseProblems(JsonNode)}). Safe to call for every save; a no-op when there are no
     * script steps.
     */
    public void enforceSave(JsonNode defJson, String rolesHeader) {
        if (scriptSteps(defJson).isEmpty()) {
            return;
        }
        if (!scriptingEnabled) {
            throw new ScriptingNotEnabledException(
                    "This definition uses a script step but scripting is disabled "
                    + "(openwcs.process-designer.scripting.enabled=false).");
        }
        if (!canAuthorScript(rolesHeader)) {
            throw new ScriptingForbiddenException(
                    "Authoring a script step requires the PROCESS_SCRIPT_AUTHOR permission.");
        }
    }

    /**
     * Publish-time validation problems for script steps: each must parse in the sandbox (no execution).
     * Returned as human-readable strings to fold into the {@link DefinitionValidator} problem list
     * (422). The flag/permission gates are enforced separately via {@link #enforceSave}.
     */
    public List<String> parseProblems(JsonNode defJson) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : scriptSteps(defJson).entrySet()) {
            JsonNode config = e.getValue().get("config");
            JsonNode scriptNode = config == null ? null : config.get("script");
            if (scriptNode == null || !scriptNode.isTextual() || scriptNode.asText().isBlank()) {
                problems.add("Script step '" + e.getKey() + "' has no script in its config.");
                continue;
            }
            try {
                runner.validateParses(scriptNode.asText());
            } catch (ScriptExecutionException ex) {
                problems.add("Script step '" + e.getKey() + "' " + ex.getMessage());
            }
        }
        return problems;
    }

    /** The task steps in a definition whose task type is {@code script}, keyed by step id. */
    private Map<String, JsonNode> scriptSteps(JsonNode defJson) {
        Map<String, JsonNode> out = new java.util.LinkedHashMap<>();
        if (defJson == null || !defJson.isObject()) {
            return out;
        }
        JsonNode steps = defJson.get("steps");
        if (steps == null || !steps.isObject()) {
            return out;
        }
        steps.fields().forEachRemaining(entry -> {
            JsonNode step = entry.getValue();
            if (step != null && step.isObject()
                    && "task".equals(asText(step, "type"))
                    && "script".equals(asText(step, "task"))) {
                out.put(entry.getKey(), step);
            }
        });
        return out;
    }

    private static String asText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() ? null : v.asText();
    }
}
