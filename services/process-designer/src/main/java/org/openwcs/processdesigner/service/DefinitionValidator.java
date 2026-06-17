package org.openwcs.processdesigner.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openwcs.processdesigner.task.TaskRegistry;
import org.springframework.stereotype.Component;

/**
 * Validates a process definition before publish (spec §10 "validate + publish", §12). Checks that:
 * <ul>
 *   <li>{@code start} exists and names a real step;</li>
 *   <li>every step is reachable from {@code start};</li>
 *   <li>every {@code next} and transition {@code to} points to an existing step;</li>
 *   <li>screen steps that capture write to a declared data-object variable ({@code writeTo});</li>
 *   <li>task steps name a curated task type that the registry knows;</li>
 *   <li>compute steps (client-evaluated variable assignment) carry a non-empty {@code set} of
 *       {@code { var, expr }} rows whose {@code var} is a declared data-object variable and whose
 *       {@code expr} parses as the value-returning expression grammar (spec; see ExpressionParser);</li>
 *   <li>every transition {@code when} and every step {@code skipWhen} parses as the restricted
 *       condition grammar (spec §6); a malformed condition fails publish;</li>
 *   <li>a step with a {@code skipWhen} (Phase 2 conditional skip) still has a reachable onward path
 *       (a {@code next} or a transition), so skipping it cannot strand the instance.</li>
 * </ul>
 * Returns the list of human-readable problems (empty = valid).
 */
@Component
public class DefinitionValidator {

    /** Screen types that must declare a writeTo (they capture a value). */
    private static final Set<String> CAPTURING_SCREENS =
            Set.of("textInput", "numberInput", "dateInput", "questionYesNo", "questionChoice");

    private final TaskRegistry taskRegistry;

    public DefinitionValidator(TaskRegistry taskRegistry) {
        this.taskRegistry = taskRegistry;
    }

    public List<String> validate(JsonNode def) {
        List<String> problems = new ArrayList<>();
        if (def == null || !def.isObject()) {
            problems.add("Definition JSON is missing or not an object.");
            return problems;
        }

        JsonNode stepsNode = def.get("steps");
        if (stepsNode == null || !stepsNode.isObject() || stepsNode.isEmpty()) {
            problems.add("Definition has no steps.");
            return problems;
        }

        Set<String> stepIds = new HashSet<>();
        stepsNode.fieldNames().forEachRemaining(stepIds::add);

        // Declared data-object variables (for writeTo resolution).
        Set<String> variables = new HashSet<>();
        JsonNode schema = def.get("dataSchema");
        if (schema != null && schema.isArray()) {
            for (JsonNode field : schema) {
                JsonNode name = field.get("name");
                if (name != null && name.isTextual()) {
                    variables.add(name.asText());
                }
            }
        }

        String start = text(def, "start");
        if (start == null) {
            problems.add("Definition has no start step.");
        } else if (!stepIds.contains(start)) {
            problems.add("Start step '" + start + "' does not exist.");
        }

        // Per-step reference checks.
        Iterator<Map.Entry<String, JsonNode>> it = stepsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String id = entry.getKey();
            JsonNode step = entry.getValue();
            String stepType = text(step, "type");

            String next = text(step, "next");
            if (next != null && !stepIds.contains(next)) {
                problems.add("Step '" + id + "' next -> '" + next + "' does not exist.");
            }

            JsonNode transitions = step.get("transitions");
            if (transitions != null && transitions.isArray()) {
                for (JsonNode t : transitions) {
                    String to = text(t, "to");
                    if (to == null) {
                        problems.add("Step '" + id + "' has a transition with no 'to'.");
                    } else if (!stepIds.contains(to)) {
                        problems.add("Step '" + id + "' transition -> '" + to + "' does not exist.");
                    }
                    String when = text(t, "when");
                    if (when != null) {
                        try {
                            ConditionParser.validate(when);
                        } catch (IllegalArgumentException e) {
                            problems.add("Step '" + id + "' transition 'when' is malformed: " + e.getMessage());
                        }
                    }
                }
            }

            // Phase 2: conditional skip. A skipWhen must parse, and a skipped step needs an onward
            // path (next or a transition) or skipping it would strand the instance.
            String skipWhen = text(step, "skipWhen");
            if (skipWhen != null) {
                try {
                    ConditionParser.validate(skipWhen);
                } catch (IllegalArgumentException e) {
                    problems.add("Step '" + id + "' skipWhen is malformed: " + e.getMessage());
                }
                boolean hasTransition = transitions != null && transitions.isArray() && !transitions.isEmpty();
                if (next == null && !hasTransition) {
                    problems.add("Step '" + id + "' has a skipWhen but no onward path "
                            + "(needs a next or a transition so a skipped step can continue).");
                }
            }

            if ("screen".equals(stepType)) {
                String screen = text(step, "screen");
                if (CAPTURING_SCREENS.contains(screen)) {
                    String writeTo = text(step.get("config"), "writeTo");
                    if (writeTo == null) {
                        problems.add("Screen step '" + id + "' (" + screen + ") has no writeTo.");
                    } else if (!variables.contains(writeTo)) {
                        problems.add("Screen step '" + id + "' writeTo '" + writeTo
                                + "' is not a declared data-object variable.");
                    }
                }
                validateVerify(problems, id, step.get("config"), variables, stepIds);
            } else if ("task".equals(stepType)) {
                String task = text(step, "task");
                if (task == null) {
                    problems.add("Task step '" + id + "' has no task type.");
                } else if (!taskRegistry.has(task)) {
                    problems.add("Task step '" + id + "' references unknown task type '" + task + "'.");
                }
            } else if ("compute".equals(stepType)) {
                validateCompute(problems, id, step, variables);
            } else {
                problems.add("Step '" + id + "' has unknown type '" + stepType
                        + "' (expected screen|task|compute).");
            }
        }

        // Reachability from start.
        if (start != null && stepIds.contains(start)) {
            Set<String> reachable = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            reachable.add(start);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                JsonNode step = stepsNode.get(cur);
                if (step == null) {
                    continue;
                }
                for (String target : targets(step)) {
                    if (stepIds.contains(target) && reachable.add(target)) {
                        queue.add(target);
                    }
                }
            }
            for (String id : stepIds) {
                if (!reachable.contains(id)) {
                    problems.add("Step '" + id + "' is unreachable from start.");
                }
            }
        }

        return problems;
    }

    /**
     * Validates an optional screen {@code config.verify} block (scan-verify step): {@code kind} must
     * be a known resolve kind; each {@code write} entry's key must be a resolvable field FOR THAT KIND
     * (per the {@code VerifyFields} catalog: a location resolves id/code/purpose/locationType/status,
     * a SKU-like kind resolves id/code/name/uomCode/schemaCategory) and its target must be a declared
     * data-object variable; and {@code onNotFound.mode=="goto"} must name an existing step. The
     * frontend handles the actual branch + writes at runtime; the server only validates the shape at
     * publish time (malformed -> the caller surfaces 422).
     */
    private static void validateVerify(List<String> problems, String id, JsonNode config,
                                       Set<String> variables, Set<String> stepIds) {
        if (config == null || !config.isObject()) {
            return;
        }
        JsonNode verify = config.get("verify");
        if (verify == null || verify.isNull()) {
            return;
        }
        if (!verify.isObject()) {
            problems.add("Screen step '" + id + "' verify is not an object.");
            return;
        }

        String kind = text(verify, "kind");
        boolean kindValid = kind != null && org.openwcs.processdesigner.verify.VerifyKinds.isValid(kind);
        if (kind == null) {
            problems.add("Screen step '" + id + "' verify has no kind.");
        } else if (!kindValid) {
            problems.add("Screen step '" + id + "' verify kind '" + kind
                    + "' is not one of " + org.openwcs.processdesigner.verify.VerifyKinds.ALL + ".");
        }

        // Resolvable field keys for THIS kind (a location resolves different attributes than a SKU).
        Set<String> validFields = kindValid
                ? org.openwcs.processdesigner.verify.VerifyFields.keysForKind(kind)
                : Set.of();

        JsonNode write = verify.get("write");
        if (write != null && !write.isNull()) {
            if (!write.isObject()) {
                problems.add("Screen step '" + id + "' verify write is not an object.");
            } else {
                Iterator<Map.Entry<String, JsonNode>> wit = write.fields();
                while (wit.hasNext()) {
                    Map.Entry<String, JsonNode> w = wit.next();
                    String field = w.getKey();
                    if (kindValid && !validFields.contains(field)) {
                        problems.add("verify field '" + field + "' is not valid for kind '" + kind
                                + "' (step '" + id + "'; valid fields for this kind: " + validFields + ").");
                    }
                    JsonNode targetNode = w.getValue();
                    String target = targetNode != null && targetNode.isTextual() ? targetNode.asText() : null;
                    if (target == null) {
                        problems.add("Screen step '" + id + "' verify write '" + field
                                + "' has no target variable.");
                    } else if (!variables.contains(target)) {
                        problems.add("Screen step '" + id + "' verify write '" + field + "' -> '" + target
                                + "' is not a declared data-object variable.");
                    }
                }
            }
        }

        JsonNode onNotFound = verify.get("onNotFound");
        if (onNotFound != null && !onNotFound.isNull()) {
            if (!onNotFound.isObject()) {
                problems.add("Screen step '" + id + "' verify onNotFound is not an object.");
            } else {
                String mode = text(onNotFound, "mode");
                if (mode == null) {
                    problems.add("Screen step '" + id + "' verify onNotFound has no mode.");
                } else if (!"reprompt".equals(mode) && !"goto".equals(mode)) {
                    problems.add("Screen step '" + id + "' verify onNotFound mode '" + mode
                            + "' is not one of [reprompt, goto].");
                } else if ("goto".equals(mode)) {
                    String target = text(onNotFound, "step");
                    if (target == null) {
                        problems.add("Screen step '" + id + "' verify onNotFound mode=goto has no step.");
                    } else if (!stepIds.contains(target)) {
                        problems.add("Screen step '" + id + "' verify onNotFound goto -> '" + target
                                + "' does not exist.");
                    }
                }
            }
        }
    }

    /**
     * Validates a {@code compute} step (no-code, client-evaluated variable assignment). The step
     * carries a non-empty {@code set} array of {@code { var, expr }} rows; the client evaluates each
     * {@code expr} in order against the data object and writes it to {@code data[var]}. The server
     * only validates the shape at publish time: {@code set} must be present and non-empty, every
     * {@code var} must be a declared data-object variable, and every {@code expr} must parse as the
     * restricted value-returning expression grammar (see {@link ExpressionParser}). A compute step is
     * NOT a server task: it has no TaskRegistry entry and is never checkpointed.
     */
    private static void validateCompute(List<String> problems, String id, JsonNode step,
                                        Set<String> variables) {
        JsonNode set = step.get("set");
        if (set == null || !set.isArray() || set.isEmpty()) {
            problems.add("Compute step '" + id + "' has an empty or missing 'set' "
                    + "(needs at least one { var, expr } assignment).");
            return;
        }
        int row = 0;
        for (JsonNode assignment : set) {
            String var = text(assignment, "var");
            String expr = text(assignment, "expr");
            if (var == null) {
                problems.add("Compute step '" + id + "' set[" + row + "] has no 'var'.");
            } else if (!variables.contains(var)) {
                problems.add("Compute step '" + id + "' set 'var' '" + var
                        + "' is not a declared data-object variable.");
            }
            if (expr == null) {
                problems.add("Compute step '" + id + "' set[" + row + "] has no 'expr'.");
            } else {
                try {
                    ExpressionParser.validate(expr);
                } catch (IllegalArgumentException e) {
                    problems.add("Compute step '" + id + "' set 'expr' '" + expr
                            + "' is malformed: " + e.getMessage());
                }
            }
            row++;
        }
    }

    private static List<String> targets(JsonNode step) {
        List<String> out = new ArrayList<>();
        String next = text(step, "next");
        if (next != null) {
            out.add(next);
        }
        JsonNode transitions = step.get("transitions");
        if (transitions != null && transitions.isArray()) {
            for (JsonNode t : transitions) {
                String to = text(t, "to");
                if (to != null) {
                    out.add(to);
                }
            }
        }
        // A screen verify onNotFound goto is a reachable onward path.
        JsonNode config = step.get("config");
        if (config != null) {
            JsonNode onNotFound = config.path("verify").path("onNotFound");
            if ("goto".equals(text(onNotFound, "mode"))) {
                String to = text(onNotFound, "step");
                if (to != null) {
                    out.add(to);
                }
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() ? null : v.asText();
    }
}
