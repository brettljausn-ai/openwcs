package org.openwcs.processdesigner.assist;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link TaskAssistModel} backed by the official Anthropic Java SDK (same dependency + graceful
 * handling as services/assistant). It forces STRUCTURED output by exposing a single tool
 * ({@code propose_task}) whose input schema is the {@link TaskAssistResult} shape and setting
 * {@code tool_choice} to that tool, so the model must return the suggestion as a tool_use input we
 * parse deterministically.
 *
 * <p>This is DESIGN-TIME only: it returns a suggestion. It never saves anything, never runs code, and
 * any drafted {@code script} is unreviewed text for the human to inspect. The {@code @Autowired}
 * constructor is the one Spring uses; the second constructor is a test seam so tests inject a mocked
 * SDK client (mirrors {@code AnthropicChatModel}; avoids the two-constructor startup crash).
 */
@Component
public class AnthropicTaskAssistModel implements TaskAssistModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTaskAssistModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_TOKENS = 1024L;
    private static final String TOOL = "propose_task";

    private final ClientFactory clientFactory;
    private final Map<String, AnthropicClient> clientsByKey = new ConcurrentHashMap<>();

    @Autowired
    public AnthropicTaskAssistModel() {
        this(key -> AnthropicOkHttpClient.builder().apiKey(key).build());
    }

    /** Test seam: supply a factory returning a (mocked) {@link AnthropicClient}. */
    public AnthropicTaskAssistModel(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /** Builds an {@link AnthropicClient} for a given API key. */
    @FunctionalInterface
    public interface ClientFactory {
        AnthropicClient create(String apiKey);
    }

    @Override
    public TaskAssistResult suggest(String apiKey, String model, String systemPrompt, String description) {
        AnthropicClient client = clientsByKey.computeIfAbsent(apiKey, clientFactory::create);
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .system(systemPrompt)
                    .addTool(proposeTool())
                    .toolChoice(ToolChoiceTool.builder().name(TOOL).build())
                    .addUserMessage(description)
                    .build();

            Message response = client.messages().create(params);
            for (ContentBlock block : response.content()) {
                if (block.toolUse().isPresent()) {
                    ToolUseBlock use = block.toolUse().get();
                    return parse(use._input());
                }
            }
            return TaskAssistResult.none("The model returned no structured suggestion.");
        } catch (AnthropicServiceException e) {
            log.warn("Anthropic task-assist call failed: {}", e.getMessage());
            throw new TaskAssistException("The AI assistant could not complete the request (model API error).");
        }
    }

    private TaskAssistResult parse(JsonValue input) {
        try {
            JsonNode node = MAPPER.readTree(input.toString());
            String kind = text(node, "kind", "none");
            String taskType = text(node, "taskType", null);
            String script = text(node, "script", null);
            String rationale = text(node, "rationale", "");
            double confidence = node.has("confidence") ? node.get("confidence").asDouble(0.0) : 0.0;
            Map<String, Object> inputs = mapOf(node.get("inputs"));
            Map<String, Object> outputs = mapOf(node.get("outputs"));
            return new TaskAssistResult(kind, taskType, inputs, outputs, script, rationale, confidence);
        } catch (Exception e) {
            log.warn("could not parse task-assist tool input: {}", e.getClass().getSimpleName());
            return TaskAssistResult.none("The model returned an unparseable suggestion.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field, String dflt) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isTextual() ? dflt : v.asText();
    }

    /** The single forced tool whose input schema IS the suggestion shape. */
    private Tool proposeTool() {
        Map<String, Object> props = Map.of(
                "kind", Map.of("type", "string", "enum", List.of("curated", "script", "none"),
                        "description", "curated = pick a curated task type and map variables; "
                                + "script = draft a sandboxed JS snippet for human review; none = nothing fits."),
                "taskType", Map.of("type", "string",
                        "description", "When kind=curated, the chosen curated task type id from the catalog."),
                "inputs", Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
                        "description", "When kind=curated, taskInputName -> data variable name (or literal:VALUE)."),
                "outputs", Map.of("type", "object", "additionalProperties", Map.of("type", "string"),
                        "description", "When kind=curated, taskOutputName -> data variable name to write."),
                "script", Map.of("type", "string",
                        "description", "When kind=script, a DRAFT JS snippet reading the frozen `data` global and "
                                + "returning a plain object of outputs. It is NOT executed; a human reviews it."),
                "rationale", Map.of("type", "string", "description", "Short explanation of the choice."),
                "confidence", Map.of("type", "number", "description", "Confidence 0..1."));

        Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)));
        Tool.InputSchema schema = Tool.InputSchema.builder()
                .properties(p.build())
                .required(List.of("kind", "rationale", "confidence"))
                .build();
        return Tool.builder()
                .name(TOOL)
                .description("Return a single structured task suggestion. Always call this tool.")
                .inputSchema(schema)
                .build();
    }
}
