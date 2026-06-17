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
                { "name": "skuId", "type": "string" },
                { "name": "uomObj", "type": "object" },
                { "name": "uomFactor", "type": "number" }
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
    void locationVerifyWriteOfSkuOnlyKeyFails422() throws Exception {
        // uomCode is a SKU-kind field; it is NOT valid for kind=location -> publish fails 422.
        String key = "verify-loc-bad-key";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"location\", \"write\": { \"uomCode\": \"skuId\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems").isArray())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("uomCode"),
                        Matchers.containsString("not valid for kind"),
                        Matchers.containsString("location")))));
    }

    @Test
    void locationVerifyWriteOfValidKeyPublishesActive() throws Exception {
        // purpose IS valid for kind=location and skuId is a declared variable -> publishes ACTIVE.
        String key = "verify-loc-ok";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"location\", \"write\": { \"purpose\": \"skuId\" },"
                + " \"onNotFound\": { \"mode\": \"goto\", \"step\": \"notFound\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void verifyWriteOfObjectWholeAndSubKeyPublishesActive() throws Exception {
        // "uom" (whole object) -> uomObj, "uom.factor" (sub-field) -> uomFactor; both declared. ACTIVE.
        String key = "verify-obj-ok";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"skuScan\","
                + " \"write\": { \"uom\": \"uomObj\", \"uom.factor\": \"uomFactor\" },"
                + " \"onNotFound\": { \"mode\": \"goto\", \"step\": \"notFound\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void verifyWriteOfUnknownSubKeyFails422() throws Exception {
        // "uom.bogus" -> uom is an object field but "bogus" is not one of its sub-fields. 422.
        String key = "verify-obj-bad-sub";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"barcode\", \"write\": { \"uom.bogus\": \"uomFactor\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("uom.bogus"),
                        Matchers.containsString("bogus"),
                        Matchers.containsString("sub-field")))));
    }

    @Test
    void verifyWriteOfSubKeyOnScalarFieldFails422() throws Exception {
        // "uomCode.x" -> uomCode is a scalar field and cannot be drilled into. 422.
        String key = "verify-scalar-drill";
        createDraft(key);
        putDraft(key, defWithVerify(key,
                "\"verify\": { \"kind\": \"barcode\", \"write\": { \"uomCode.x\": \"skuId\" } }"));
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", EDITOR).header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.problems", Matchers.hasItem(Matchers.allOf(
                        Matchers.containsString("uomCode.x"),
                        Matchers.containsString("scalar field")))));
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
