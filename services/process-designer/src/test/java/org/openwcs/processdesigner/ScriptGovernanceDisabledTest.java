package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Governance with scripting DISABLED (the default flag, as {@link AbstractIntegrationTest} runs):
 * saving a definition that carries a script step is rejected 422 even for ADMIN.
 */
class ScriptGovernanceDisabledTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void savingScriptStepIsRejectedWhenScriptingDisabled() throws Exception {
        String key = "script-off";
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Off\"}"))
                .andExpect(status().isCreated());

        String def = scriptDef(key, "return { x: 1 };");
        // 422: scripting disabled, regardless of permission.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(def))
                .andExpect(status().isUnprocessableEntity());
    }

    static String scriptDef(String key, String script) {
        return """
            {
              "processKey": "%s",
              "title": "S",
              "dataSchema": [ { "name": "x", "type": "number" } ],
              "start": "calc",
              "steps": {
                "calc": { "type": "task", "task": "script",
                          "config": { "script": "%s", "outputs": [ { "name": "x" } ] },
                          "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": {} }
              }
            }
            """.formatted(key, script);
    }
}
