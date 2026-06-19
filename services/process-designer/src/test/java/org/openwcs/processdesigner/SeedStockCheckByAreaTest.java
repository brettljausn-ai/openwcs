package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Flyway V5 seed ships an ACTIVE "Stock Check by Area (reference)" process that exercises the new
 * Master Data Areas pieces: verify (kind area), stockcheck.next (with the running instanceId
 * auto-injected), a decision on found, a count loop, and work.release on the abandon/empty path.
 * Duplicating + publishing the seeded model runs the full publish validation over the exact JSON
 * (verify writes including the new area kind, task types, decision routes, reachability), so a broken
 * reference flow fails the build rather than only at runtime.
 */
class SeedStockCheckByAreaTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void referenceStockCheckByAreaIsSeededActive() throws Exception {
        mvc.perform(get("/api/process-designer/defs/stock-check-by-area/active").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.steps.pickArea.config.verify.kind").value("area"))
                .andExpect(jsonPath("$.steps.getNext.task").value("stockcheck.next"))
                .andExpect(jsonPath("$.steps.decideFound.type").value("decision"))
                .andExpect(jsonPath("$.steps.releaseWork.task").value("work.release"));
    }

    @Test
    void seededAreaModelPassesPublishValidation() throws Exception {
        String dup = mvc.perform(post("/api/process-designer/defs/stock-check-by-area/1/duplicate")
                        .header("X-Auth-Roles", "SUPERVISOR").header("X-Auth-User", "alice"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int version = mapper.readTree(dup).get("version").asInt();

        mvc.perform(post("/api/process-designer/defs/stock-check-by-area/" + version + "/publish")
                        .header("X-Auth-Roles", "SUPERVISOR").header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
