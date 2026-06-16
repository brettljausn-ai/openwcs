package org.openwcs.processdesigner.task;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Server-side registry of the curated task library (spec §7.1). Maps a task-type string (e.g.
 * {@code "inventory.move"}) to its handler. New task types are added in code and shipped via the
 * normal PR/CI pipeline — there is no API to author tasks (spec §12). All discovered
 * {@link ProcessTask} beans are auto-registered by their {@link ProcessTask#type()}.
 */
@Component
public class TaskRegistry {

    private final Map<String, ProcessTask> tasks;

    public TaskRegistry(List<ProcessTask> discovered) {
        this.tasks = discovered.stream()
                .collect(Collectors.toMap(ProcessTask::type, Function.identity()));
    }

    /** True if a task type is known to the curated library. */
    public boolean has(String type) {
        return tasks.containsKey(type);
    }

    /** The handler for a task type, or null if unknown. */
    public ProcessTask get(String type) {
        return tasks.get(type);
    }

    /** The known task-type strings (for validation + docs). */
    public java.util.Set<String> types() {
        return tasks.keySet();
    }
}
