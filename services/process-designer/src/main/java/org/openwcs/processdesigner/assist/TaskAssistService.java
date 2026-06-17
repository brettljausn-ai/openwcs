package org.openwcs.processdesigner.assist;

import java.util.List;
import java.util.stream.Collectors;
import org.openwcs.processdesigner.task.TaskRegistry;
import org.openwcs.processdesigner.task.TaskSpec;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a design-time AI task-assist turn (spec §7.3): rejects when AI is not configured
 * (503), grounds the model with the LIVE curated task catalog (types/labels/required inputs/outputs)
 * and the caller-provided data variables, and delegates to the {@link TaskAssistModel} for a forced
 * structured suggestion. The result is a SUGGESTION only — never saved, never executed. Any drafted
 * script is unreviewed text for the human to inspect. The Anthropic key never leaves this layer.
 */
@Service
public class TaskAssistService {

    private final AiAssistConfig config;
    private final TaskAssistModel model;
    private final TaskRegistry taskRegistry;

    public TaskAssistService(AiAssistConfig config, TaskAssistModel model, TaskRegistry taskRegistry) {
        this.config = config;
        this.model = model;
        this.taskRegistry = taskRegistry;
    }

    public boolean configured() {
        return config.configured();
    }

    /** Produce a suggestion. Throws {@link AssistNotConfiguredException} (503) when no key is set. */
    public TaskAssistResult suggest(TaskAssistRequest request) {
        if (!config.configured()) {
            throw new AssistNotConfiguredException("AI assist not configured");
        }
        String prompt = systemPrompt(request.variablesOrEmpty());
        return model.suggest(config.apiKey(), config.model(), prompt, request.description());
    }

    private String systemPrompt(List<TaskAssistRequest.Variable> variables) {
        String catalog = taskRegistry.catalog().stream().map(this::describeTask)
                .collect(Collectors.joining("\n"));
        String vars = variables.isEmpty()
                ? "(none declared)"
                : variables.stream()
                        .map(v -> "- " + v.name() + " (" + (v.type() == null ? "string" : v.type()) + ")")
                        .collect(Collectors.joining("\n"));
        return """
            You are a design-time assistant inside the openWCS handheld process designer. A non-developer \
            describes what a TASK STEP should achieve; you propose the best way to build it. You output a \
            SUGGESTION only — nothing you return is saved, deployed, or executed.

            Prefer, in order:
            1. kind="curated": pick the single best-fit curated task type from the catalog below and map \
            its inputs to the available data variables (or literal:VALUE constants) and its outputs to \
            data variables. Use ONLY task types and input/output names from the catalog, and ONLY the \
            variable names listed.
            2. kind="script": if nothing in the catalog fits, draft a SANDBOXED JavaScript snippet. The \
            snippet runs in a locked-down sandbox: it reads a frozen global `data` object (the data \
            variables) and must return a plain object of output values. No host/Java access, no network, \
            no imports. Keep it short. Make clear in the rationale that this is an UNREVIEWED DRAFT a \
            developer/designer must review before use.
            3. kind="none": if neither fits, say so in the rationale.

            Always call the propose_task tool exactly once with your structured answer.

            CURATED TASK CATALOG:
            %s

            AVAILABLE DATA VARIABLES:
            %s
            """.formatted(catalog.isBlank() ? "(none)" : catalog, vars);
    }

    private String describeTask(TaskSpec s) {
        String inputs = s.inputs().stream()
                .map(i -> i.name() + (i.required() ? "*" : ""))
                .collect(Collectors.joining(", "));
        String outputs = s.outputs().stream().map(TaskSpec.Output::name).collect(Collectors.joining(", "));
        String desc = s.description() == null || s.description().isBlank() ? "" : ": " + s.description();
        return "- " + s.type() + " (\"" + s.label() + "\")" + desc
                + " inputs: [" + inputs + "] outputs: [" + outputs + "]";
    }
}
