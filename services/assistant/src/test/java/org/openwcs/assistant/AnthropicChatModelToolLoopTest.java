package org.openwcs.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.services.blocking.MessageService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openwcs.assistant.chat.AnthropicChatModel;
import org.openwcs.assistant.chat.AssistantContext;
import org.openwcs.assistant.chat.ChatMessage;
import org.openwcs.assistant.tools.WarehouseToolClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Drives the manual tool-use loop with a mocked Anthropic SDK client and a {@link MockRestServiceServer}
 * standing in for the warehouse services. The mocked Claude returns a {@code tool_use} (get_dashboard)
 * on the first turn, then a final text answer on the second. Asserts that:
 * <ul>
 *   <li>the tool HTTP GET was actually made (against order-management's dashboard endpoint), and</li>
 *   <li>the caller's {@code X-Auth-*} identity headers were forwarded onto that call, and</li>
 *   <li>the model's final answer is returned.</li>
 * </ul>
 * The SDK {@code Message} responses are deserialized from JSON via the SDK's own mapper (so we don't
 * have to populate every required builder field). No real Anthropic calls are made.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnthropicChatModelToolLoopTest {

    @AfterEach
    void clearContext() {
        AssistantContext.clear();
    }

    @Test
    void runsToolLoopForwardsIdentityAndReturnsFinalAnswer() {
        String warehouse = "11111111-1111-1111-1111-111111111111";

        // RestClient whose interceptor forwards X-Auth-* headers (mirrors IdentityForwardingConfig).
        RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
            request.getHeaders().add("X-Auth-User", "alice");
            request.getHeaders().add("X-Auth-Roles", "OPERATOR");
            request.getHeaders().add("X-Auth-Warehouses", warehouse);
            return execution.execute(request, body);
        });
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://orders.test/api/orders/reports/dashboard?warehouseId=" + warehouse))
                .andExpect(header("X-Auth-User", "alice"))
                .andExpect(header("X-Auth-Roles", "OPERATOR"))
                .andExpect(header("X-Auth-Warehouses", warehouse))
                .andRespond(withSuccess("{\"outbound\":7,\"inbound\":2}", MediaType.APPLICATION_JSON));

        WarehouseToolClient toolClient = new WarehouseToolClient(
                builder, "http://orders.test", "http://inv.test", "http://flow.test", "http://alloc.test");

        // Mocked Anthropic SDK: turn 1 = tool_use(get_dashboard), turn 2 = final text.
        AnthropicClient sdk = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(sdk.messages()).thenReturn(messages);
        // create(MessageCreateParams) is a default method — doReturn(...) stubs it without invoking
        // the real default-method body during stubbing (which when(...) would do).
        doReturn(toolUseMessage("tool_1", "get_dashboard"),
                finalTextMessage("You have 7 outbound and 2 inbound orders today."))
                .when(messages).create(any(MessageCreateParams.class));

        AnthropicChatModel model = new AnthropicChatModel(toolClient, key -> sdk);

        AssistantContext.setWarehouseId(warehouse);
        String reply = model.chat("sk-test", "claude-haiku-4-5", "system",
                List.of(new ChatMessage("user", "give me today's order dashboard")));

        server.verify(); // the tool HTTP GET (with forwarded headers) was made
        assertThat(reply).isEqualTo("You have 7 outbound and 2 inbound orders today.");
    }

    private static com.anthropic.models.messages.Message toolUseMessage(String toolUseId, String toolName) {
        return parse("""
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5",
             "stop_reason":"tool_use","stop_sequence":null,
             "content":[{"type":"tool_use","id":"%s","name":"%s","input":{}}],
             "usage":{"input_tokens":1,"output_tokens":1}}
            """.formatted(toolUseId, toolName));
    }

    private static com.anthropic.models.messages.Message finalTextMessage(String text) {
        return parse("""
            {"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-4-5",
             "stop_reason":"end_turn","stop_sequence":null,
             "content":[{"type":"text","text":"%s","citations":null}],
             "usage":{"input_tokens":1,"output_tokens":1}}
            """.formatted(text));
    }

    private static com.anthropic.models.messages.Message parse(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json, com.anthropic.models.messages.Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
