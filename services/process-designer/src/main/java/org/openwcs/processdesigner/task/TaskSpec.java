package org.openwcs.processdesigner.task;

import java.util.List;

/**
 * Self-describing metadata for a curated task type, served by the task-catalog endpoint
 * ({@code GET /api/process-designer/tasks}) so the designer's task picker + input/output mapping is
 * driven by the real server registry rather than a hardcoded client list. {@code description} is a
 * one-sentence explanation of what the task does and when to use it; {@code inputs} declares the
 * named inputs a task reads (each with {@code required} + a {@code description} of what to map into
 * it); {@code outputs} the named values it merges back into the data object (each with a
 * {@code description} of what the value means).
 */
public record TaskSpec(String type, String label, String description, List<Input> inputs,
                       List<Output> outputs) {

    /**
     * A named task input. {@code required} drives the designer's mapping validation;
     * {@code description} tells the process author what to map into it.
     */
    public record Input(String name, boolean required, String description) {
    }

    /**
     * A named task output merged back into the data object; {@code description} explains what the
     * returned value means.
     */
    public record Output(String name, String description) {
    }

    /** Convenience builder for a required input with a description. */
    public static Input req(String name, String description) {
        return new Input(name, true, description);
    }

    /** Convenience builder for an optional input with a description. */
    public static Input opt(String name, String description) {
        return new Input(name, false, description);
    }

    /** Convenience builder for an output with a description. */
    public static Output out(String name, String description) {
        return new Output(name, description);
    }
}
