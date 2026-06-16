package org.openwcs.processdesigner.task;

import java.util.List;

/**
 * Self-describing metadata for a curated task type, served by the task-catalog endpoint
 * ({@code GET /api/process-designer/tasks}) so the designer's task picker + input/output mapping is
 * driven by the real server registry rather than a hardcoded client list. {@code inputs} declares
 * the named inputs a task reads (with {@code required}); {@code outputs} the named values it merges
 * back into the data object.
 */
public record TaskSpec(String type, String label, List<Input> inputs, List<Output> outputs) {

    /** A named task input; {@code required} drives the designer's mapping validation. */
    public record Input(String name, boolean required) {
    }

    /** A named task output merged back into the data object. */
    public record Output(String name) {
    }

    /** Convenience builder for a required input. */
    public static Input req(String name) {
        return new Input(name, true);
    }

    /** Convenience builder for an optional input. */
    public static Input opt(String name) {
        return new Input(name, false);
    }

    /** Convenience builder for an output. */
    public static Output out(String name) {
        return new Output(name);
    }
}
