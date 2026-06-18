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
 * The Flyway seed ships an ACTIVE "Stock Count (reference)" process that exercises the designer end
 * to end: verify (location + skuScan), inventory.lookup -> expectedQty, a compute match flag, and a
 * decision step that posts the count or loops back for a recount. This test also DUPLICATES the
 * seeded model into a draft and PUBLISHES it, which runs the full publish validation over the exact
 * seeded JSON (verify write paths, compute variables, decision routes, transitions, reachability) -
 * so a broken reference flow fails the build rather than only at runtime on a handheld.
 */
class SeedReferenceTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Test
    void referenceStockCountIsSeededActiveWithDecisionAndCompute() throws Exception {
        mvc.perform(get("/api/process-designer/defs/stock-count-ref/active").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.steps.decideCount.type").value("decision"))
                .andExpect(jsonPath("$.steps.computeMatch.type").value("compute"))
                .andExpect(jsonPath("$.steps.lookupExpected.task").value("inventory.lookup"))
                .andExpect(jsonPath("$.steps.scanLocation.config.verify.kind").value("location"));
    }

    @Test
    void seededReferenceModelPassesPublishValidation() throws Exception {
        // Duplicate the seeded ACTIVE v1 into a fresh DRAFT, then publish it: this validates the exact
        // seeded JSON against every publish rule (verify writes, compute vars, decision routes, etc.).
        String dup = mvc.perform(post("/api/process-designer/defs/stock-count-ref/1/duplicate")
                        .header("X-Auth-Roles", "SUPERVISOR").header("X-Auth-User", "alice"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        int version = mapper.readTree(dup).get("version").asInt();

        mvc.perform(post("/api/process-designer/defs/stock-count-ref/" + version + "/publish")
                        .header("X-Auth-Roles", "SUPERVISOR").header("X-Auth-User", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
