package org.openwcs.process.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/** Request/response shapes for deploying definitions and running process instances. */
public final class ProcessDtos {

    private ProcessDtos() {
    }

    public record ProcessDefinitionView(String id, String key, String name, int version) {
    }

    /** Start a process instance of {@code processKey} with optional businessKey + variables. */
    public record StartInstanceRequest(@NotBlank String processKey, String businessKey,
                                       Map<String, Object> variables) {
    }

    public record InstanceView(String id, String processDefinitionKey, String businessKey, boolean ended) {
    }

    /** A user/wait task awaiting completion. */
    public record TaskView(String id, String name, String assignee, String processInstanceId) {
    }
}
