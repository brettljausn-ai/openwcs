package org.openwcs.processdesigner;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openwcs.processdesigner.assist.TaskAssistModel;
import org.openwcs.processdesigner.assist.TaskAssistResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * AI task-assist (spec §7.3) with the {@link TaskAssistModel} mocked (no real Anthropic call) and a
 * key configured: a description matching a catalog task yields a curated suggestion; a description
 * that fits nothing yields a script-draft (or none). The endpoint is a SUGGESTION only — these assert
 * the response shape, not any server-state change.
 */
@SpringBootTest(properties = {
        "openwcs.security.enabled=true",
        "openwcs.process-designer.assist.api-key=sk-test"
})
@AutoConfigureMockMvc
class AssistControllerTest {

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

    @MockBean
    TaskAssistModel model;

    @Test
    void returnsCuratedSuggestionForMatchingDescription() throws Exception {
        when(model.suggest(eq("sk-test"), anyString(), anyString(), anyString()))
                .thenReturn(new TaskAssistResult("curated", "inventory.move",
                        Map.of("warehouseId", "warehouseId", "huId", "huId"),
                        Map.of(), null, "inventory.move fits a move task.", 0.9));

        mvc.perform(post("/api/process-designer/assist/task")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"description":"move a handling unit to a new location",
                             "variables":[{"name":"warehouseId","type":"string"},
                                          {"name":"huId","type":"hu"}]}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("curated"))
                .andExpect(jsonPath("$.taskType").value("inventory.move"))
                .andExpect(jsonPath("$.confidence").value(0.9));
    }

    @Test
    void returnsScriptDraftWhenNothingFits() throws Exception {
        when(model.suggest(eq("sk-test"), anyString(), anyString(), anyString()))
                .thenReturn(new TaskAssistResult("script", null, null, null,
                        "return { vip: data.qty > 100 };",
                        "DRAFT for review: nothing in the catalog computes this.", 0.4));

        mvc.perform(post("/api/process-designer/assist/task")
                        .header("X-Auth-Roles", "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"description":"flag the order VIP when qty over 100",
                             "variables":[{"name":"qty","type":"number"},{"name":"vip","type":"boolean"}]}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("script"))
                .andExpect(jsonPath("$.script").value("return { vip: data.qty > 100 };"));
    }

    @Test
    void assistRequiresEditPermission() throws Exception {
        // VIEWER lacks PROCESS_DESIGN_EDIT.
        mvc.perform(post("/api/process-designer/assist/task")
                        .header("X-Auth-Roles", "VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"x\",\"variables\":[]}"))
                .andExpect(status().isForbidden());
    }
}
