package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Definition lifecycle: create DRAFT -> edit -> publish flips ACTIVE and archives the prior ACTIVE,
 * one-ACTIVE-per-key holds, and publishing an invalid def returns 422 with problems.
 */
class DefinitionLifecycleTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";

    @Autowired
    MockMvc mvc;

    private String validDraftBody(String key, String title) {
        return """
            {
              "processKey": "%s",
              "title": "%s",
              "icon": "inbound",
              "dataSchema": [ { "name": "asn", "type": "string" } ],
              "start": "scanAsn",
              "steps": {
                "scanAsn": { "type": "screen", "screen": "textInput",
                             "config": { "writeTo": "asn", "validation": { "required": true } },
                             "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": { "confirmLabel": "OK" } }
              }
            }
            """.formatted(key, title);
    }

    @Test
    void createEditPublishFlipsActiveAndArchivesPrior() throws Exception {
        String key = "goods-in";

        // Create v1 DRAFT.
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Goods In\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // Edit v1 with a valid model.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody(key, "Goods In")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.scanAsn.next").value("done"));

        // Publish v1 -> ACTIVE.
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR)
                        .header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.publishedBy").value("alice"));

        // active endpoint returns v1.
        mvc.perform(get("/api/process-designer/defs/" + key + "/active").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Create + publish v2; v1 must be archived (one ACTIVE per key).
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Goods In v2\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(put("/api/process-designer/defs/" + key + "/2")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody(key, "Goods In v2")))
                .andExpect(status().isOk());

        mvc.perform(post("/api/process-designer/defs/" + key + "/2/publish")
                        .header("X-Auth-Roles", EDITOR)
                        .header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // active is now v2.
        mvc.perform(get("/api/process-designer/defs/" + key + "/active").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // v1 is ARCHIVED.
        mvc.perform(get("/api/process-designer/defs/" + key + "/1").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
    }

    @Test
    void editingNonDraftReturns409() throws Exception {
        String key = "edit-conflict";
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"X\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody(key, "X")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "bob"))
                .andExpect(status().isOk());
        // Editing the now-ACTIVE version -> 409.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody(key, "X")))
                .andExpect(status().isConflict());
    }

    @Test
    void publishingInvalidDefinitionReturns422WithProblems() throws Exception {
        String key = "broken";
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Broken\"}"))
                .andExpect(status().isCreated());

        // Dangling next + missing start + unknown task.
        String broken = """
            {
              "processKey": "broken",
              "title": "Broken",
              "dataSchema": [],
              "start": "nope",
              "steps": {
                "a": { "type": "screen", "screen": "acknowledge", "config": {}, "next": "ghost" },
                "b": { "type": "task", "task": "does.not.exist", "next": "a" }
              }
            }
            """;
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "bob"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }
}
