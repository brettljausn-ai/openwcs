package org.openwcs.processdesigner;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Flyway seed ships an ACTIVE "Stock Check" process so the feature is demoable end-to-end:
 * scan location -> scan SKU -> count -> txlog.post task -> ack.
 */
class SeedTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void stockCheckIsSeededActive() throws Exception {
        mvc.perform(get("/api/process-designer/defs/stock-check/active").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processKey").value("stock-check"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.steps.postCount.task").value("txlog.post"));
    }

    @Test
    void seededProcessAppearsInMenu() throws Exception {
        mvc.perform(get("/api/process-designer/processes").header("X-Auth-Roles", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.processKey=='stock-check')].activeVersion").value(
                        org.hamcrest.Matchers.hasItem(1)));
    }
}
