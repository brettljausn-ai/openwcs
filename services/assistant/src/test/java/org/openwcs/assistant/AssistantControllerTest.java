package org.openwcs.assistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.openwcs.assistant.chat.ChatModel;
import org.openwcs.assistant.config.AiConfig;
import org.openwcs.assistant.config.AiConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller test (no Anthropic calls): the {@link ChatModel} (the {@code AnthropicChatModel} bean is
 * replaced by type) and {@link AiConfigProvider} are mocked. Asserts the chat endpoint returns the
 * model's reply when configured, returns 409 when the assistant is disabled, and the /status shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssistantControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    AiConfigProvider configProvider;

    @MockBean
    ChatModel chatModel;

    @Test
    void chatReturnsReplyWhenConfigured() throws Exception {
        when(configProvider.current()).thenReturn(new AiConfig("sk-test", "claude-haiku-4-5", true));
        when(chatModel.chat(eq("sk-test"), eq("claude-haiku-4-5"), anyString(), anyList()))
                .thenReturn("There are 3 open orders.");

        mvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"warehouseId":"11111111-1111-1111-1111-111111111111",
                             "messages":[{"role":"user","content":"how many open orders?"}]}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("There are 3 open orders."));
    }

    @Test
    void chatReturns409WhenDisabled() throws Exception {
        when(configProvider.current()).thenReturn(new AiConfig(null, null, false));

        mvc.perform(post("/api/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"warehouseId":"11111111-1111-1111-1111-111111111111",
                             "messages":[{"role":"user","content":"hi"}]}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AI assistant not configured"));
    }

    @Test
    void statusReportsShapeWithoutKey() throws Exception {
        when(configProvider.current()).thenReturn(new AiConfig("sk-secret", "claude-opus-4-8", true));

        mvc.perform(get("/api/assistant/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("claude-opus-4-8"))
                // The key must never appear in the status payload.
                .andExpect(jsonPath("$.apiKey").doesNotExist());
    }
}
