package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RBAC: a viewer (PROCESS_DESIGN_VIEW) can read definitions but cannot create/publish; an editor
 * (PROCESS_DESIGN_EDIT via SUPERVISOR) can. With no roles, even reads are forbidden when security
 * is enabled.
 */
class RbacTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void viewerCanReadButNotWrite() throws Exception {
        // Read (defs list) allowed for VIEWER.
        mvc.perform(get("/api/process-designer/defs").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/process-designer/processes").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk());

        // Create draft forbidden for VIEWER.
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"x\",\"title\":\"X\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noRolesIsForbidden() throws Exception {
        mvc.perform(get("/api/process-designer/defs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorCanCreate() throws Exception {
        mvc.perform(post("/api/process-designer/defs")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processKey\":\"rbac-ok\",\"title\":\"OK\"}"))
                .andExpect(status().isCreated());
    }
}
