package org.openwcs.masterdata;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * AI-assistant config: GET is open and shows enabled=false/configured=false until an admin sets it
 * up; PUT is ADMIN-only and audited; the API key is write-only (never echoed) but readable
 * server-side via the network-only {@code /internal/ai-assistant} endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AiAssistantConfigTest {

    private static final String ROLES = "X-Auth-Roles";
    private static final String SECRET = "sk-ant-secret-xyz-123";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SystemConfigurationRepository config;

    @BeforeEach
    void clearAiAssistantRows() {
        // @SpringBootTest shares one database across methods; start each from the unset defaults.
        config.deleteAllById(java.util.List.of(
                "AI_ASSISTANT_API_KEY", "AI_ASSISTANT_MODEL", "AI_ASSISTANT_ENABLED"));
    }

    @Test
    void defaultsDisabledAndUnconfigured() throws Exception {
        mockMvc.perform(get("/api/master-data/ai-assistant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.model").value("claude-haiku-4-5"));
    }

    @Test
    void nonAdminCannotUpdate() throws Exception {
        mockMvc.perform(put("/api/master-data/ai-assistant")
                        .header(ROLES, "SUPERVISOR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidModelIsRejected() throws Exception {
        mockMvc.perform(put("/api/master-data/ai-assistant")
                        .header(ROLES, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminConfiguresThenKeyIsNeverEchoedButReadableInternally() throws Exception {
        // Admin sets key + model + enabled. The key is never in the response body.
        mockMvc.perform(put("/api/master-data/ai-assistant")
                        .header(ROLES, "ADMIN")
                        .header("X-Auth-User", "admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + SECRET + "\",\"model\":\"claude-opus-4-8\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                .andExpect(content().string(not(containsString(SECRET))));

        // GET shows configured + model + enabled, still without the key.
        mockMvc.perform(get("/api/master-data/ai-assistant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                .andExpect(content().string(not(containsString(SECRET))));

        // The internal endpoint returns the actual stored key.
        mockMvc.perform(get("/internal/ai-assistant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(SECRET))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void updateWithoutApiKeyPreservesTheExistingKey() throws Exception {
        // Set a key first.
        mockMvc.perform(put("/api/master-data/ai-assistant")
                        .header(ROLES, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"" + SECRET + "\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true));

        // Toggle model/enabled without re-sending the key: it stays configured.
        mockMvc.perform(put("/api/master-data/ai-assistant")
                        .header(ROLES, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"claude-opus-4-8\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"));

        // The original key is still readable internally.
        mockMvc.perform(get("/internal/ai-assistant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").value(SECRET));
    }
}
