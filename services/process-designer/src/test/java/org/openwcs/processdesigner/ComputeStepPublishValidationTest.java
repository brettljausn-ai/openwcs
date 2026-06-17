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
 * Publish-time validation of the no-code {@code compute} step (client-evaluated variable
 * assignment). The motivating example (stock counting): after the operator enters qty, set
 * {@code match = qty == expected or qty == prevCount} and {@code prevCount = qty}, then branch on
 * {@code match}. A valid compute step publishes ACTIVE; an undeclared var, a malformed expr, or an
 * empty set each fails publish with 422.
 */
class ComputeStepPublishValidationTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";

    @Autowired
    MockMvc mvc;

    /** A counting flow whose compute step carries the given set JSON (the inner [...] contents). */
    private String defWithComputeSet(String key, String setJson) {
        return ("""
            {
              "processKey": "%s",
              "title": "Count Flow",
              "dataSchema": [
                { "name": "qty", "type": "number" },
                { "name": "expected", "type": "number" },
                { "name": "prevCount", "type": "number" },
                { "name": "match", "type": "boolean" }
              ],
              "start": "enterQty",
              "steps": {
                "enterQty": { "type": "screen", "screen": "numberInput",
                              "config": { "writeTo": "qty" }, "next": "calcMatch" },
                "calcMatch": { "type": "compute",
                               "set": %s,
                               "transitions": [ { "when": "match == true", "to": "done" } ],
                               "next": "recount" },
                "recount": { "type": "screen", "screen": "acknowledge",
                             "config": { "confirmLabel": "Recount" }, "next": "enterQty" },
                "done": { "type": "screen", "screen": "acknowledge",
                          "config": { "confirmLabel": "OK" } }
              }
            }
            """).formatted(key, setJson);
    }

    private void createDraft(String key) throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Count Flow\"}"))
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
    void validComputeStepPublishesActive() throws Exception {
        String key = "compute-ok";
        createDraft(key);
        putDraft(key, defWithComputeSet(key,
                "[ { \"var\": \"match\", \"expr\": \"qty == expected or qty == prevCount\" },"
                + " { \"var\": \"prevCount\", \"expr\": \"qty\" } ]"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void computeVarUndeclaredFails422() throws Exception {
        String key = "compute-bad-var";
        createDraft(key);
        putDraft(key, defWithComputeSet(key,
                "[ { \"var\": \"ghostVar\", \"expr\": \"qty\" } ]"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("ghostVar"),
                        Matchers.containsString("not a declared data-object variable")))));
    }

    @Test
    void computeMalformedExprFails422() throws Exception {
        String key = "compute-bad-expr";
        createDraft(key);
        putDraft(key, defWithComputeSet(key,
                "[ { \"var\": \"match\", \"expr\": \"qty ==\" } ]"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.containsString("malformed"))));
    }

    @Test
    void computeEmptySetFails422() throws Exception {
        String key = "compute-empty-set";
        createDraft(key);
        putDraft(key, defWithComputeSet(key, "[ ]"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.containsString("empty or missing 'set'"))));
    }
}
