package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Default config ({@link AbstractIntegrationTest}: scripting OFF, no Anthropic key):
 * <ul>
 *   <li>{@code POST /assist/task} returns 503 "AI assist not configured" (context still started);</li>
 *   <li>{@code GET /capabilities} reflects scriptingEnabled=false, aiAssistEnabled=false; canAuthorScript
 *       follows the caller's permission (ADMIN true, SUPERVISOR false).</li>
 * </ul>
 */
class AssistAndCapabilitiesDefaultTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void assistReturns503WhenNotConfigured() throws Exception {
        mvc.perform(post("/api/process-designer/assist/task")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"do a thing\",\"variables\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("AI assist not configured"));
    }

    @Test
    void capabilitiesReflectFlagAndPermission() throws Exception {
        // Scripting off + no key; ADMIN holds PROCESS_SCRIPT_AUTHOR.
        mvc.perform(get("/api/process-designer/capabilities").header("X-Auth-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scriptingEnabled").value(false))
                .andExpect(jsonPath("$.aiAssistEnabled").value(false))
                .andExpect(jsonPath("$.canAuthorScript").value(true))
                // Server-driven scan-verify kinds for the designer's Verify picker.
                .andExpect(jsonPath("$.verifyKinds").isArray())
                .andExpect(jsonPath("$.verifyKinds[0]").value("barcode"))
                .andExpect(jsonPath("$.verifyKinds[1]").value("sku"))
                .andExpect(jsonPath("$.verifyKinds[2]").value("location"));

        // SUPERVISOR can view (PROCESS_DESIGN_VIEW) but cannot author scripts.
        mvc.perform(get("/api/process-designer/capabilities").header("X-Auth-Roles", "SUPERVISOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAuthorScript").value(false));
    }
}
