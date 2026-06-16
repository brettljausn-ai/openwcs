package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Governance with scripting ENABLED:
 * <ul>
 *   <li>a SUPERVISOR (PROCESS_DESIGN_EDIT but NOT PROCESS_SCRIPT_AUTHOR) saving a script step is 403;</li>
 *   <li>an ADMIN (both) saving a valid script step is allowed and publishes;</li>
 *   <li>publishing a definition whose script is malformed is 422.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "openwcs.security.enabled=true",
        "openwcs.process-designer.scripting.enabled=true"
})
@AutoConfigureMockMvc
class ScriptGovernanceEnabledTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void supervisorWithoutScriptAuthorIsForbidden() throws Exception {
        String key = "script-perm";
        createDraft(key, "SUPERVISOR");
        // 403: SUPERVISOR has edit but not PROCESS_SCRIPT_AUTHOR.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ScriptGovernanceDisabledTest.scriptDef(key, "return { x: 1 };")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminWithBothCanSaveAndPublish() throws Exception {
        String key = "script-ok";
        createDraft(key, "ADMIN");
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ScriptGovernanceDisabledTest.scriptDef(key, "return { x: 1 };")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", "ADMIN").header("X-Auth-User", "alice"))
                .andExpect(status().isOk());
    }

    @Test
    void malformedScriptFailsPublish() throws Exception {
        String key = "script-bad";
        createDraft(key, "ADMIN");
        // Saves fine (no parse check at save), publish runs the parse-only sandbox check -> 422.
        mvc.perform(put("/api/process-designer/defs/" + key + "/1")
                        .header("X-Auth-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ScriptGovernanceDisabledTest.scriptDef(key, "return { x: ")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/process-designer/defs/" + key + "/1/publish")
                        .header("X-Auth-Roles", "ADMIN").header("X-Auth-User", "alice"))
                .andExpect(status().isUnprocessableEntity());
    }

    private void createDraft(String key, String role) throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"" + key + "\",\"title\":\"S\"}"))
                .andExpect(status().isCreated());
    }
}
