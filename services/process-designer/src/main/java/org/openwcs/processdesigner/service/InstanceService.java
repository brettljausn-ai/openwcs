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
import org.openwcs.processdesigner.api.StepEventView;
import org.openwcs.processdesigner.domain.ProcessDefinition;
import org.openwcs.processdesigner.domain.ProcessInstance;
import org.openwcs.processdesigner.domain.ProcessStepEvent;
import org.openwcs.processdesigner.domain.ProcessStepResult;
import org.openwcs.processdesigner.repo.ProcessInstanceRepository;
import org.openwcs.processdesigner.repo.ProcessStepEventRepository;
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
    private final ProcessStepEventRepository stepEvents;
    private final DefinitionService definitions;
    private final TaskRegistry taskRegistry;
    private final ObjectMapper mapper;

    public InstanceService(ProcessInstanceRepository instances, ProcessStepResultRepository stepResults,
                           ProcessStepEventRepository stepEvents, DefinitionService definitions,
                           TaskRegistry taskRegistry, ObjectMapper mapper) {
        this.instances = instances;
        this.stepResults = stepResults;
        this.stepEvents = stepEvents;
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
        // The starter owns the run until a supervisor reassigns it (startedBy stays the audit of
        // who created it, assignedTo drives "my open work" / resume-by-user).
        instance.setAssignedTo(startedBy);
        instance.setWarehouseId(warehouseId);
        ProcessInstance saved = instances.save(instance);
        log.info("process instance {} started: {} v{} at step '{}'",
                saved.getId(), processKey, def.getVersion(), start);
        return view(saved, defJson);
    }

    /**
     * Instance monitoring list (spec §14): newest-first summaries, optionally filtered by processKey /
     * status / warehouseId / assignedTo, capped at {@code limit} (clamped 1..500, default 50). The
     * assignedTo filter drives "my open work" / resume-by-user.
     */
    @Transactional(readOnly = true)
    public List<InstanceSummary> list(String processKey, String status, UUID warehouseId, String assignedTo,
                                      Integer limit) {
        int capped = limit == null ? 50 : Math.max(1, Math.min(limit, 500));
        String key = blankToNull(processKey);
        String st = status == null || status.isBlank() ? null : status.toUpperCase();
        String assignee = blankToNull(assignedTo);
        return instances.search(key, st, warehouseId, assignee,
                        org.springframework.data.domain.PageRequest.of(0, capped))
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
    public CheckpointResult checkpoint(UUID instanceId, String stepId, JsonNode posted, Long seq, String actor) {
        ProcessInstance instance = load(instanceId);

        // Idempotency: a re-post of an already-processed (instance, step) returns the stored result.
        // The step-history event was written on the first run, so a re-post does NOT append a duplicate.
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
        // The sandboxed script task is special: its payload (script + declared outputs) lives in the
        // step config and it reads the whole data object, so feed it through reserved input keys.
        Map<String, Object> inputs = "script".equals(taskType)
                ? scriptInputs(step.get("config"), data)
                : resolveInputs(step.get("input"), data);
        // Reserved context: curated tasks get the running instance's warehouse without the operator
        // scanning it (an explicit input mapping still wins). Lets stock/lookup tasks work as-is. The
        // running instance id rides along the same way, so area work-claim/release tasks can tag the
        // claim with the instance that holds it without the designer mapping anything.
        if (!"script".equals(taskType)) {
            if (instance.getWarehouseId() != null) {
                inputs.putIfAbsent("warehouseId", instance.getWarehouseId().toString());
            }
            inputs.putIfAbsent("instanceId", instance.getId().toString());
            // The operator's username rides along so a task that stamps an actor onto a downstream
            // transaction (e.g. a txlog event's actor field) persists who actually did it. The
            // forwarded X-Auth-* headers still carry identity for RBAC; this is the in-body record.
            if (actor != null && !actor.isBlank()) {
                inputs.putIfAbsent("actor", actor);
            }
        }

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
        // Append a step-history event so replay is continuous across screen advances + task steps.
        // The recorded step_type is the definition step's type ("task"), matching how screen advances
        // record their type. Guarded by the ledger above (a re-post returned early), so it never
        // double-appends.
        appendStepEvent(instance, seq, stepId, text(step, "type"), data, actor);
        log.info("checkpoint {}#{} ran task '{}' -> next '{}'{}",
                instanceId, stepId, taskType, next, done ? " (instance done)" : "");
        return new CheckpointResult(data, instance.getCurrentStep(), done);
    }

    /**
     * Record a per-screen step advance (spec: per-screen step history for exact resume + replay). The
     * mobile client posts this on every SCREEN advance so the server's current step + data object are
     * exact for a mid-flow device swap. Idempotent on (instanceId, seq): re-posting the same seq
     * returns the current state without re-applying. Otherwise it appends the event (entered_by =
     * actor), merges the posted data into the instance, sets current_step = stepId, and touches the
     * instance.
     */
    @Transactional
    public InstanceView step(UUID instanceId, long seq, String stepId, String stepType, JsonNode posted,
                             String actor) {
        ProcessInstance instance = load(instanceId);
        ProcessDefinition def = definitions.get(instance.getProcessKey(), instance.getDefVersion());
        if (stepEvents.existsByInstanceIdAndSeq(instanceId, seq)) {
            log.info("step {}#{} (seq {}) already recorded; returning current state (idempotent)",
                    instanceId, stepId, seq);
            return view(instance, def.getJson());
        }
        ObjectNode data = mergedData(instance.getData(), posted);
        instance.setData(data);
        if (stepId != null) {
            instance.setCurrentStep(stepId);
        }
        stepEvents.save(new ProcessStepEvent(instanceId, seq, stepId, stepType, data, actor));
        log.info("step {}#{} (seq {}, type {}) recorded by {}", instanceId, stepId, seq, stepType, actor);
        return view(instance, def.getJson());
    }

    /**
     * Reassign a running instance to another operator (supervisor-gated at the controller). Sets
     * assigned_to = toUser, keeps started_by (the immutable audit of who created the run), and writes
     * an audit step event ({@code step_type = "reassign"}, data = {@code {from, to}}).
     */
    @Transactional
    public InstanceView reassign(UUID instanceId, String toUser, String actor) {
        ProcessInstance instance = load(instanceId);
        ProcessDefinition def = definitions.get(instance.getProcessKey(), instance.getDefVersion());
        String from = instance.getAssignedTo();
        instance.setAssignedTo(toUser);
        ObjectNode auditData = mapper.createObjectNode();
        auditData.put("from", from);
        auditData.put("to", toUser);
        stepEvents.save(new ProcessStepEvent(instanceId, nextSeq(instanceId), instance.getCurrentStep(),
                "reassign", auditData, actor));
        log.info("instance {} reassigned from {} to {} by {}", instanceId, from, toUser, actor);
        return view(instance, def.getJson());
    }

    /** The instance's step history in order (oldest first), for replay / resume. */
    @Transactional(readOnly = true)
    public List<StepEventView> steps(UUID instanceId) {
        load(instanceId); // 404 if the instance does not exist
        return stepEvents.findByInstanceIdOrderBySeq(instanceId).stream().map(StepEventView::from).toList();
    }

    /** Append a step-history event, deriving the seq server-side when the caller did not supply one. */
    private void appendStepEvent(ProcessInstance instance, Long seq, String stepId, String stepType,
                                 JsonNode data, String actor) {
        long resolved = seq != null ? seq : nextSeq(instance.getId());
        // If the resolved seq is already taken (e.g. a client-supplied seq collides), fall back to the
        // next free seq rather than failing the whole checkpoint on the history row.
        if (stepEvents.existsByInstanceIdAndSeq(instance.getId(), resolved)) {
            resolved = nextSeq(instance.getId());
        }
        stepEvents.save(new ProcessStepEvent(instance.getId(), resolved, stepId, stepType, data, actor));
    }

    /** The next free monotonic seq for the instance (max recorded + 1, starting at 1). */
    private long nextSeq(UUID instanceId) {
        Long max = stepEvents.maxSeq(instanceId);
        return max == null ? 1L : max + 1L;
    }

    /**
     * Build the reserved inputs for the sandboxed script task (spec §7.2) from the step's config:
     * the snippet text ({@code config.script}), the declared output names ({@code config.outputs}, a
     * list of {@code {name}}), and a read-only copy of the current data object. The runner exposes
     * {@code data} to the snippet and merges the snippet's returned object back as outputs.
     */
    private Map<String, Object> scriptInputs(JsonNode config, ObjectNode data) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(org.openwcs.processdesigner.task.ScriptTask.SCRIPT_KEY, text(config, "script"));
        inputs.put(org.openwcs.processdesigner.task.ScriptTask.DATA_KEY, data.deepCopy());
        List<String> outputs = new java.util.ArrayList<>();
        JsonNode declared = config == null ? null : config.get("outputs");
        if (declared != null && declared.isArray()) {
            for (JsonNode o : declared) {
                String name = text(o, "name");
                if (name != null) {
                    outputs.add(name);
                }
            }
        }
        inputs.put(org.openwcs.processdesigner.task.ScriptTask.OUTPUTS_KEY, outputs);
        return inputs;
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
                defJson, i.getData(), i.getCurrentStep(), i.getAssignedTo());
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() ? null : v.asText();
    }
}
