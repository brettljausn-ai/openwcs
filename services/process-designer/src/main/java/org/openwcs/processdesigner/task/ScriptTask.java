package org.openwcs.processdesigner.task;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.openwcs.processdesigner.script.SandboxedScriptRunner;
import org.openwcs.processdesigner.script.ScriptExecutionException;
import org.springframework.stereotype.Component;

/**
 * Curated task type {@code script} (spec §7.2): the controlled scripting escape hatch. It runs a
 * designer-authored JavaScript snippet in the locked-down {@link SandboxedScriptRunner} and merges
 * the snippet's returned object back as the task's output variables. It executes through the same
 * checkpoint / task-execution path as every other {@link ProcessTask}; the engine hands it the step's
 * {@code config.script} + declared {@code config.outputs} and a read-only copy of the running data
 * object (under the reserved input keys below), since a script step's payload lives in its config
 * rather than a flat input mapping.
 *
 * <p>This task NEVER compiles or runs designer code as host JVM bytecode — the snippet runs only
 * inside the GraalJS interpreter sandbox (no host/Java access, statement + wall-clock limits). A
 * parse error, limit breach, timeout, or sandbox-escape attempt throws {@link ScriptExecutionException}
 * (a clean task failure; the service stays up). Authoring a script step is additionally gated by the
 * {@code PROCESS_SCRIPT_AUTHOR} permission and the off-by-default scripting flag at save/publish time.
 */
@Component
public class ScriptTask implements ProcessTask {

    /** Reserved input key carrying the snippet text (the step's {@code config.script}). */
    public static final String SCRIPT_KEY = "__script";
    /** Reserved input key carrying the read-only data object ({@link JsonNode}). */
    public static final String DATA_KEY = "__data";
    /** Reserved input key carrying the declared output names ({@code List<String>}). */
    public static final String OUTPUTS_KEY = "__outputs";

    private final SandboxedScriptRunner runner;

    public ScriptTask(SandboxedScriptRunner runner) {
        this.runner = runner;
    }

    @Override
    public String type() {
        return "script";
    }

    @Override
    public TaskSpec spec() {
        // No declared inputs/outputs: the snippet reads the whole `data` object and returns named
        // outputs declared per-step in config, so the generic input/output mapping does not apply.
        return new TaskSpec("script", "Sandboxed script",
                "Run a designer-authored JavaScript snippet in a locked-down sandbox; its inputs and "
                        + "outputs are declared per-step in the step's config, not on this catalog entry.",
                List.of(), List.of());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> inputs) {
        Object scriptObj = inputs.get(SCRIPT_KEY);
        if (!(scriptObj instanceof String script) || script.isBlank()) {
            throw new ScriptExecutionException("Script step has no script in its config.");
        }
        JsonNode data = inputs.get(DATA_KEY) instanceof JsonNode n ? n : null;
        List<String> outputs = new ArrayList<>();
        if (inputs.get(OUTPUTS_KEY) instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    outputs.add(String.valueOf(o));
                }
            }
        }
        return runner.run(script, data, outputs);
    }
}
