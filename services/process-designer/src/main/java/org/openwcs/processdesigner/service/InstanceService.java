package org.openwcs.processdesigner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.openwcs.processdesigner.api.CheckpointResult;
import org.openwcs.processdesigner.api.InstanceSummary;
import org.openwcs.processdesigner.api.InstanceView;
import org.openwcs.processdesigner.api.NotFoundException;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.openwcs.processdesigner.domain.ProcessInstance;
import org.openwcs.processdesigner.domain.ProcessStepResult;
import org.openwcs.processdesigner.repo.ProcessInstanceRepository;
import org.openwcs.processdesigner.repo.ProcessStepResultRepository;
import org.openwcs.processdesigner.task.ProcessTask;
import org.openwcs.processdesigner.task.TaskExecutionException;
import org.openwcs.processdesigner.task.TaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instance lifecycle + the server-side task-step checkpoint (spec §9). Starting an instance pins
 * the ACTIVE definition version. A checkpoint:
 * <ol>
 *   <li>asserts the step is a task step in the pinned definition;</li>
 *   <li>is idempotent on (instanceId, stepId) — a re-post returns the stored result without
 *       re-running side effects (dedupe via the {@code process_step_result} ledger);</li>
 *   <li>merges the posted data, runs the curated task (identity forwarded by the RestClient
 *       interceptor), merges the task's named outputs back, and advances {@code current_step};</li>
 *   <li>on task failure throws {@link TaskExecutionException} (502) and does NOT advance.</li>
 * </ol>
 */
@Service
public class InstanceService {

    private static final Logger log = LoggerFactory.getLogger(InstanceService.class);

    private final ProcessInstanceRepository instances;
    private final ProcessStepResultRepository stepResults;
    private final DefinitionService definitions;
    private final TaskRegistry taskRegistry;
    private final ObjectMapper mapper;

    public InstanceService(ProcessInstanceRepository instances, ProcessStepResultRepository stepResults,
                           DefinitionService definitions, TaskRegistry taskRegistry, ObjectMapper mapper) {
        this.instances = instances;
        this.stepResults = stepResults;
        this.definitions = definitions;
        this.taskRegistry = taskRegistry;
        this.mapper = mapper;
    }

    @Transactional
    public InstanceView start(String processKey, UUID warehouseId, String startedBy) {
        ProcessDefinition def = definitions.active(processKey);
        JsonNode defJson = def.getJson();
        String start = text(defJson, "start");

        ProcessInstance instance = new ProcessInstance();
        instance.setProcessKey(processKey);
        instance.setDefVersion(def.getVersion());
        instance.setStatus(ProcessInstance.RUNNING);
        instance.setData(mapper.createObjectNode());
        instance.setCurrentStep(start);
        instance.setStartedBy(startedBy);
        instance.setWarehouseId(warehouseId);
        ProcessInstance saved = instances.save(instance);
        log.info("process instance {} started: {} v{} at step '{}'",
                saved.getId(), processKey, def.getVersion(), start);
        return view(saved, defJson);
    }

    /**
     * Instance monitoring list (spec §14): newest-first summaries, optionally filtered by processKey /
     * status / warehouseId, capped at {@code limit} (clamped 1..500, default 50).
     */
    @Transactional(readOnly = true)
    public List<InstanceSummary> list(String processKey, String status, UUID warehouseId, Integer limit) {
        int capped = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        String key = blankToNull(processKey);
        String st = status == null || status.isBlank() ? null : status.toUpperCase();
        return instances.search(key, st, warehouseId, org.springframework.data.domain.PageRequest.of(0, capped))
                .stream().map(InstanceSummary::from).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Transactional(readOnly = true)
    public InstanceView resume(UUID instanceId) {
        ProcessInstance instance = load(instanceId);
        ProcessDefinition def = definitions.get(instance.getProcessKey(), instance.getDefVersion());
        return view(instance, def.getJson());
    }

    @Transactional
    public CheckpointResult checkpoint(UUID instanceId, String stepId, JsonNode posted) {
        ProcessInstance instance = load(instanceId);

        // Idempotency: a re-post of an already-processed (instance, step) returns the stored result.
        var prior = stepResults.findByInstanceIdAndStepId(instanceId, stepId);
        if (prior.isPresent()) {
            ProcessStepResult r = prior.get();
            log.info("checkpoint {}#{} already processed; returning stored result (idempotent)", instanceId, stepId);
            return new CheckpointResult(r.getResultData(), r.getNextStep(), r.isDone());
        }

        ProcessDefinition def = definitions.get(instance.getProcessKey(), instance.getDefVersion());
        JsonNode steps = def.getJson().get("steps");
        JsonNode step = steps == null ? null : steps.get(stepId);
        if (step == null) {
            throw new NotFoundException("Step '" + stepId + "' not in definition "
                    + instance.getProcessKey() + " v" + instance.getDefVersion());
        }
        if (!"task".equals(text(step, "type"))) {
            throw new IllegalArgumentException("Step '" + stepId + "' is not a task step");
        }
        String taskType = text(step, "task");
        ProcessTask task = taskRegistry.get(taskType);
        if (task == null) {
            throw new IllegalArgumentException("Unknown task type '" + taskType + "' for step '" + stepId + "'");
        }

        // Merge the client-posted data into the instance data object first.
        ObjectNode data = mergedData(instance.getData(), posted);

        // Resolve the step's input mapping (variable/literal -> value) into the task's named inputs.
        Map<String, Object> inputs = resolveInputs(step.get("input"), data);

        // Run the curated task. A failure aborts the transaction (no ledger row, no advance) -> 502.
        Map<String, Object> outputs = task.execute(inputs);

        // Merge the task's named outputs back, applying the step's output renaming if present.
        applyOutputs(data, step.get("output"), outputs);

        // Advance: unconditional `next` (branching transitions are evaluated client-side over the
        // returned data; a task step in Phase 1 advances by its default `next`).
        String next = text(step, "next");
        boolean done = next == null;

        instance.setData(data);
        instance.setCurrentStep(done ? null : next);
        if (done) {
            instance.setStatus(ProcessInstance.DONE);
        }

        // Record the idempotency ledger row (same transaction as the instance update).
        stepResults.save(new ProcessStepResult(instanceId, stepId, data, next, done));
        log.info("checkpoint {}#{} ran task '{}' -> next '{}'{}",
                instanceId, stepId, taskType, next, done ? " (instance done)" : "");
        return new CheckpointResult(data, instance.getCurrentStep(), done);
    }

    private ProcessInstance load(UUID instanceId) {
        return instances.findById(instanceId)
                .orElseThrow(() -> new NotFoundException("Instance " + instanceId + " not found"));
    }

    private ObjectNode mergedData(JsonNode base, JsonNode posted) {
        ObjectNode data = base != null && base.isObject()
                ? ((ObjectNode) base).deepCopy()
                : mapper.createObjectNode();
        if (posted != null && posted.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = posted.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                data.set(e.getKey(), e.getValue());
            }
        }
        return data;
    }

    /**
     * Resolve a step's {@code input} mapping into the task's named inputs. Each mapping value is the
     * name of a data-object variable, or {@code literal:VALUE} for a constant. A missing variable
     * resolves to null (the task validates required inputs itself).
     */
    private Map<String, Object> resolveInputs(JsonNode inputMapping, ObjectNode data) {
        Map<String, Object> inputs = new HashMap<>();
        if (inputMapping == null || !inputMapping.isObject()) {
            return inputs;
        }
        Iterator<Map.Entry<String, JsonNode>> it = inputMapping.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String taskInput = e.getKey();
            JsonNode ref = e.getValue();
            if (ref == null || !ref.isTextual()) {
                continue;
            }
            String expr = ref.asText();
            if (expr.startsWith("literal:")) {
                inputs.put(taskInput, expr.substring("literal:".length()));
            } else {
                inputs.put(taskInput, jsonToJava(data.get(expr)));
            }
        }
        return inputs;
    }

    /**
     * Merge a task's outputs back into the data object. If the step has an {@code output} mapping
     * ({@code dataVar -> taskOutputName}) it renames as it merges; otherwise outputs are merged
     * under their own names.
     */
    private void applyOutputs(ObjectNode data, JsonNode outputMapping, Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        if (outputMapping != null && outputMapping.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = outputMapping.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String dataVar = e.getKey();
                JsonNode src = e.getValue();
                if (src != null && src.isTextual() && outputs.containsKey(src.asText())) {
                    data.set(dataVar, mapper.valueToTree(outputs.get(src.asText())));
                }
            }
        } else {
            for (Map.Entry<String, Object> e : outputs.entrySet()) {
                data.set(e.getKey(), mapper.valueToTree(e.getValue()));
            }
        }
    }

    private Object jsonToJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    private InstanceView view(ProcessInstance i, JsonNode defJson) {
        return new InstanceView(i.getId(), i.getProcessKey(), i.getDefVersion(), i.getStatus(),
                defJson, i.getData(), i.getCurrentStep());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() ? null : v.asText();
    }
}
