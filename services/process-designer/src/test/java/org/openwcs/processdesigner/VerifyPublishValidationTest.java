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
 * Publish-time validation of the screen {@code config.verify} block: a write target that is not a
 * declared data-object variable, and an {@code onNotFound.goto} to a missing step, each fail publish
 * with 422; a well-formed verify publishes ACTIVE.
 */
class VerifyPublishValidationTest extends AbstractIntegrationTest {

    private static final String EDITOR = "SUPERVISOR";

    @Autowired
    MockMvc mvc;

    /** A flow whose scan screen carries a verify block; %s is the verify JSON object. */
    private String defWithVerify(String key, String verifyJson) {
        return ("""
            {
              "processKey": "%s",
              "title": "Verify Flow",
              "dataSchema": [
                { "name": "scanned", "type": "string" },
                { "name": "skuId", "type": "string" }
              ],
              "start": "scan",
              "steps": {
                "scan": { "type": "screen", "screen": "textInput",
                          "config": { "writeTo": "scanned", %s },
                          "next": "done" },
                "notFound": { "type": "screen", "screen": "acknowledge", "config": { "confirmLabel": "Retry" },
                              "next": "done" },
                "done": { "type": "screen", "screen": "acknowledge", "config": { "confirmLabel": "OK" } }
              }
            }
            """).formatted(key, verifyJson);
    }

    private void createDraft(String key) throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", EDITOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"Verify Flow\"}"))
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
    void verifyWriteToUnknownVariableFails422() throws Exception {
        String key = "verify-bad-write";
        createDraft(key);
        // write maps the resolved id to a variable that is NOT in dataSchema.
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"barcode\", \"write\": { \"id\": \"ghostVar\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems.length()").value(Matchers.greaterThan(0)));
    }

    @Test
    void verifyOnNotFoundGotoMissingStepFails422() throws Exception {
        String key = "verify-bad-goto";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"location\", \"onNotFound\": { \"mode\": \"goto\", \"step\": \"nowhere\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems.length()").value(Matchers.greaterThan(0)));
    }

    @Test
    void validVerifyPublishesActive() throws Exception {
        String key = "verify-ok";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"barcode\", \"write\": { \"id\": \"skuId\" },"
                + " \"onNotFound\": { \"mode\": \"goto\", \"step\": \"notFound\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
