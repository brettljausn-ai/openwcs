package org.openwcs.processdesigner.task;

import java.util.List;
import java.util.Map;

/**
 * A curated, parameterized task type (spec §7.1). A task reads named inputs (already resolved from
 * the running instance's data object by the engine, per the step's {@code input} mapping), calls
 * an existing audited openWCS endpoint with the operator's forwarded identity, and returns named
 * outputs to be merged back into the data object.
 *
 * <p>Tasks are pure side-effecting handlers: idempotency is the engine's responsibility (it dedupes
 * on instance + step before invoking the task). A failure must throw {@link TaskExecutionException}
 * so the engine returns a 502 and does NOT advance the instance.
 */
public interface ProcessTask {

    /** The task-type string used in a step's {@code "task"} field, e.g. {@code "inventory.move"}. */
    String type();

    /**
     * Run the task.
     *
     * @param inputs the resolved named inputs for this step (variable name → value from the data object)
     * @return named outputs to merge back into the data object (may be empty)
     */
    Map<String, Object> execute(Map<String, Object> inputs);

    /**
     * Self-describing catalog metadata (label + named inputs/outputs) served by
     * {@code GET /api/process-designer/tasks} so the designer's task picker is driven by the real
     * registry. The default is a bare spec (label = type, no declared inputs/outputs) for internal /
     * test-only tasks; curated tasks override it.
     */
    default TaskSpec spec() {
        return new TaskSpec(type(), type(), "", List.of(), List.of());
    }
}
