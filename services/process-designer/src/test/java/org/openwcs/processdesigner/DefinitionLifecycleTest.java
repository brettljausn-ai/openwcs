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

    @Test
    void duplicateClonesPublishedVersionIntoNewDraft() throws Exception {
        String key = "dup-flow";
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Dup\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validDraftBody(key, "Dup")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk());

        // Duplicate the ACTIVE v1 -> a fresh DRAFT v2 cloned from its JSON.
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/duplicate")
                        .header("X-Auth-Roles", EDITOR))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        // The clone carries the source's steps, with version/status overridden.
        mvc.perform(get("/api/process-designer/defs/" + key + "/2").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.steps.scanAsn.next").value("done"));

        // v1 is still ACTIVE (duplicate never mutates the source).
        mvc.perform(get("/api/process-designer/defs/" + key + "/1").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void importCreatesDraftFromFullDocument() throws Exception {
        String key = "import-flow";
        String doc = """
            {
              "processKey": "%s",
              "version": 99,
              "status": "ACTIVE",
              "title": "Imported",
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
            """.formatted(key);

        // Incoming version/status are ignored; a fresh DRAFT v1 is created.
        mvc.perform(post("/api/process-designer/defs/import")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(doc))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.title").value("Imported"));

        mvc.perform(get("/api/process-designer/defs/" + key + "/1").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.steps.scanAsn.writeTo").doesNotExist())
                .andExpect(jsonPath("$.steps.scanAsn.config.writeTo").value("asn"));
    }

    @Test
    void validSkipWhenPublishesButMalformedSkipWhenFails422() throws Exception {
        String key = "skip-flow";
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Skip\"}"))
                .andExpect(status().isCreated());

        String withSkip = """
            {
              "processKey": "%s",
              "title": "Skip",
              "dataSchema": [ { "name": "asn", "type": "string" }, { "name": "damaged", "type": "boolean" } ],
              "start": "scanAsn",
              "steps": {
                "scanAsn": { "type": "screen", "screen": "textInput",
                             "config": { "writeTo": "asn", "validation": { "required": true } },
                             "next": "maybe" },
                "maybe": { "type": "screen", "screen": "acknowledge", "config": { "confirmLabel": "OK" },
                           "skipWhen": "%s", "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": { "confirmLabel": "Done" } }
              }
            }
            """;

        // A valid skipWhen publishes cleanly.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withSkip.formatted(key, "damaged == true and not (asn == 'X')")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // A new DRAFT v2 with a malformed skipWhen fails publish with 422.
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Skip2\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/process-designer/defs/" + key + "/2")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withSkip.formatted(key, "damaged ==")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/2/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        // A new DRAFT v3 whose skipWhen references an UNDECLARED variable fails publish with 422.
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Skip3\"}"))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/process-designer/defs/" + key + "/3")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withSkip.formatted(key, "ghostFlag == true")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/3/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems", org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("ghostFlag"),
                        org.hamcrest.Matchers.containsString("not a declared data-object variable")))));
    }

    @Test
    void taskCatalogListsRegisteredTypesWithInputsAndOutputs() throws Exception {
        mvc.perform(get("/api/process-designer/tasks").header("X-Auth-Roles", EDITOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // The new + existing curated tasks are present, with inputs/outputs.
                .andExpect(jsonPath("$[?(@.type=='host.confirm')]").exists())
                .andExpect(jsonPath("$[?(@.type=='inventory.adjust')]").exists())
                .andExpect(jsonPath("$[?(@.type=='counting.capture')]").exists())
                .andExpect(jsonPath("$[?(@.type=='order.lookup')]").exists())
                .andExpect(jsonPath("$[?(@.type=='inventory.move')]").exists())
                // Master Data Areas tasks: area work-claim (counting/slotting) + release.
                .andExpect(jsonPath("$[?(@.type=='stockcheck.next')].inputs[?(@.name=='areaId' && @.required==true)]").exists())
                .andExpect(jsonPath("$[?(@.type=='stockcheck.next')].outputs[?(@.name=='lineId')]").exists())
                .andExpect(jsonPath("$[?(@.type=='replenishment.next')].inputs[?(@.name=='areaId' && @.required==true)]").exists())
                .andExpect(jsonPath("$[?(@.type=='replenishment.next')].outputs[?(@.name=='toLocationId')]").exists())
                .andExpect(jsonPath("$[?(@.type=='work.release')]").exists())
                .andExpect(jsonPath("$[?(@.type=='counting.capture')].inputs[?(@.name=='countedQty' && @.required==true)]").exists())
                .andExpect(jsonPath("$[?(@.type=='order.lookup')].outputs[?(@.name=='orderRef')]").exists())
                // Each curated task carries a human description, and inputs/outputs are described too.
                .andExpect(jsonPath("$[?(@.type=='inventory.lookup' && @.description != '' && @.description)]").exists())
                .andExpect(jsonPath("$[?(@.type=='inventory.lookup')].inputs[?(@.name=='skuId' && @.description != '' && @.description)]").exists())
                .andExpect(jsonPath("$[?(@.type=='inventory.lookup')].outputs[?(@.name=='onHand' && @.description != '' && @.description)]").exists());
    }
}
