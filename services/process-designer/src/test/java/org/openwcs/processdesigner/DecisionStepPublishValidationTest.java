package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Publish-time validation of the no-code {@code decision} step (client-evaluated router / gateway:
 * if / elseif / else). A decision step has no screen, task or set: its ordered {@code transitions}
 * are the if/elseif rules (first matching {@code when} wins) and {@code next} is the else/default
 * target. The runtime routes through it without rendering or a checkpoint, so the server only stores
 * and validates it. A valid decision step publishes ACTIVE; an undeclared {@code when} variable, a
 * dead-end decision (no transitions and no next), or a transition to a missing step each fails 422.
 */
class DecisionStepPublishValidationTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";

    @Autowired
    MockMvc mvc;

    /** A flow whose {@code route} decision step carries the given transitions JSON and next clause. */
    private String defWithDecision(String key, String transitionsJson, String nextClause) {
        return ("""
            {
              "processKey": "%s",
              "title": "Route Flow",
              "dataSchema": [
                { "name": "qty", "type": "number" },
                { "name": "expected", "type": "number" }
              ],
              "start": "enterQty",
              "steps": {
                "enterQty": { "type": "screen", "screen": "numberInput",
                              "config": { "writeTo": "qty" }, "next": "route" },
                "route": { "type": "decision",
                           "transitions": %s%s },
                "tooLow": { "type": "screen", "screen": "acknowledge",
                            "config": { "confirmLabel": "Too low" }, "next": "enterQty" },
                "tooHigh": { "type": "screen", "screen": "acknowledge",
                             "config": { "confirmLabel": "Too high" }, "next": "enterQty" },
                "done": { "type": "screen", "screen": "acknowledge",
                          "config": { "confirmLabel": "OK" } }
              }
            }
            """).formatted(key, transitionsJson, nextClause);
    }

    private void createDraft(String key) throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Route Flow\"}"))
                .andExpect(status().isCreated());
    }

    private void putDraft(String key, String body) throws Exception {
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void validDecisionStepWithTwoBranchesAndDefaultPublishesActive() throws Exception {
        String key = "decision-ok";
        createDraft(key);
        putDraft(key, defWithDecision(key,
                "[ { \"when\": \"qty < expected\", \"to\": \"tooLow\" },"
                + " { \"when\": \"qty > expected\", \"to\": \"tooHigh\" } ]",
                ", \"next\": \"done\""));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void decisionWhenReferencingUndeclaredVarFails422() throws Exception {
        String key = "decision-bad-var";
        createDraft(key);
        putDraft(key, defWithDecision(key,
                "[ { \"when\": \"qty < mysteryVar\", \"to\": \"tooLow\" } ]",
                ", \"next\": \"done\""));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("mysteryVar"),
                        Matchers.containsString("not a declared data-object variable")))));
    }

    @Test
    void decisionWithNoTransitionsAndNoNextFails422() throws Exception {
        String key = "decision-dead-end";
        createDraft(key);
        putDraft(key, defWithDecision(key, "[ ]", ""));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(
                        Matchers.containsString("has no outgoing route"))));
    }

    @Test
    void decisionTransitionToMissingStepFails422() throws Exception {
        String key = "decision-bad-to";
        createDraft(key);
        putDraft(key, defWithDecision(key,
                "[ { \"when\": \"qty < expected\", \"to\": \"ghostStep\" } ]",
                ", \"next\": \"done\""));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("ghostStep"),
                        Matchers.containsString("does not exist")))));
    }
}
