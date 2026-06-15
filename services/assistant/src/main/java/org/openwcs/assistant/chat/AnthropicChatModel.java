package org.openwcs.assistant.chat;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.openwcs.assistant.tools.WarehouseToolClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link ChatModel} backed by the official Anthropic Java SDK, running a manual tool-use agentic loop.
 *
 * <p>Each iteration builds a {@link MessageCreateParams} (model, maxTokens, system prompt, the running
 * conversation, all tool definitions) and calls {@code client.messages().create(...)}. Any
 * {@code tool_use} blocks in the response are executed as read-only HTTP GETs against the warehouse
 * services (via {@link WarehouseToolClient}, which forwards the caller's identity headers); the
 * results are appended as a user message carrying {@code tool_result} blocks, and the loop repeats
 * until the model stops requesting tools (or a safety cap is hit). The concatenated final text blocks
 * are returned.
 *
 * <p>The warehouseId for tool calls is captured per-request via a thread-local set by the caller, so
 * tools always scope to the active warehouse even though the model only supplies the optional
 * parameters (sku/huId/status/etc.).
 */
@Component
public class AnthropicChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(AnthropicChatModel.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_ITERATIONS = 6;
    private static final long MAX_TOKENS = 4096L;

    private final WarehouseToolClient tools;
    // How an AnthropicClient is created for a key. Overridable in tests to inject a mock SDK client.
    private final ClientFactory clientFactory;
    // Cache the SDK client per API key so we don't rebuild OkHttp/connection pools every request.
    private final Map<String, AnthropicClient> clientsByKey = new ConcurrentHashMap<>();

    public AnthropicChatModel(WarehouseToolClient tools) {
        this(tools, key -> AnthropicOkHttpClient.builder().apiKey(key).build());
    }

    /** Test seam: supply a factory that returns a (mocked) {@link AnthropicClient}. */
    public AnthropicChatModel(WarehouseToolClient tools, ClientFactory clientFactory) {
        this.tools = tools;
        this.clientFactory = clientFactory;
    }

    /** Builds an {@link AnthropicClient} for a given API key. */
    @FunctionalInterface
    public interface ClientFactory {
        AnthropicClient create(String apiKey);
    }

    @Override
    public String chat(String apiKey, String model, String systemPrompt, List<ChatMessage> messages) {
        AnthropicClient client = clientsByKey.computeIfAbsent(apiKey, clientFactory::create);

        List<Tool> toolDefs = toolDefinitions();
        // Build the running conversation from the incoming user/assistant turns.
        List<MessageParam> conversation = new ArrayList<>();
        for (ChatMessage m : messages) {
            MessageParam.Role role = "assistant".equalsIgnoreCase(m.role())
                    ? MessageParam.Role.ASSISTANT : MessageParam.Role.USER;
            conversation.add(MessageParam.builder().role(role).content(m.content()).build());
        }

        try {
            for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                MessageCreateParams.Builder params = MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(MAX_TOKENS)
                        .system(systemPrompt);
                for (Tool t : toolDefs) {
                    params.addTool(t);
                }
                for (MessageParam mp : conversation) {
                    params.addMessage(mp);
                }

                Message response = client.messages().create(params.build());

                List<ToolUseBlock> toolUses = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    block.toolUse().ifPresent(toolUses::add);
                }

                if (toolUses.isEmpty()) {
                    // No tool calls — this is the final answer.
                    return finalText(response);
                }

                // Append the assistant's response (with its tool_use blocks) ...
                conversation.add(assistantTurn(response));
                // ... then a user turn carrying one tool_result per tool_use.
                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock use : toolUses) {
                    String result = executeTool(use.name(), use._input());
                    results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(use.id())
                            .content(result)
                            .build()));
                }
                conversation.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
            }
            // Hit the iteration cap without a clean end_turn.
            return "I gathered some data but could not finish reasoning about it. Please try a more specific question.";
        } catch (AnthropicServiceException e) {
            log.warn("Anthropic API call failed: {}", e.getMessage());
            throw new ChatModelException("The AI assistant could not complete the request (model API error).", e);
        }
    }

    private MessageParam assistantTurn(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            blocks.add(block.toParam());
        }
        return MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private String finalText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> sb.append(t.text()));
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "I don't have an answer for that from the available warehouse data." : text;
    }

    /** Execute one tool by name using the parsed input map, scoped to the active warehouse. */
    private String executeTool(String name, JsonValue input) {
        Map<String, Object> args = parseInput(input);
        String warehouseId = AssistantContext.warehouseId();
        try {
            return switch (name) {
                case "search_orders" ->
                        tools.searchOrders(warehouseId, str(args, "status"), intOf(args, "limit"));
                case "get_stock_by_sku" -> tools.stockBySku(warehouseId);
                case "get_inventory_dashboard" -> tools.inventoryDashboard(warehouseId);
                case "get_hu_trace" -> tools.huTrace(str(args, "huId"), warehouseId);
                case "get_transport_tasks" ->
                        tools.transportTasks(warehouseId, str(args, "status"), intOf(args, "limit"));
                case "get_stock_blocking" -> tools.stockBlocking(warehouseId);
                case "get_dashboard" -> tools.ordersDashboard(warehouseId);
                default -> "{\"error\":\"unknown tool\"}";
            };
        } catch (Exception e) {
            log.warn("tool execution error for {}: {}", name, e.getClass().getSimpleName());
            return "{\"error\":\"tool execution failed\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(JsonValue input) {
        try {
            Object parsed = MAPPER.readValue(input.toString(), Object.class);
            if (parsed instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        } catch (Exception ignored) {
            // fall through to empty
        }
        return Map.of();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Integer intOf(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** The read-only tool catalog offered to Claude. warehouseId is injected by the loop, not the model. */
    private List<Tool> toolDefinitions() {
        List<Tool> defs = new ArrayList<>();

        defs.add(tool("search_orders",
                "Find orders in the active warehouse, optionally filtered by status. Use for questions "
                        + "about inbound/outbound orders, their status (e.g. CREATED, RELEASED, SHIPPED), and counts.",
                Map.of(
                        "status", strProp("Optional order status to filter by, e.g. CREATED, RELEASED, ALLOCATED, SHIPPED, CANCELLED."),
                        "limit", intProp("Optional max number of orders to return (default 20, max 50).")),
                List.of()));

        defs.add(tool("get_stock_by_sku",
                "Stock availability per SKU in the active warehouse. Use for questions about how much of "
                        + "an item is on hand or available.",
                Map.of(), List.of()));

        defs.add(tool("get_inventory_dashboard",
                "Inventory headline for the active warehouse: storage utilisation, handling-unit and SKU "
                        + "counts. Use for 'how full is the warehouse' / 'how many HUs or SKUs' questions.",
                Map.of(), List.of()));

        defs.add(tool("get_hu_trace",
                "Trace a handling unit (HU): where it currently is and its transport timeline. Requires the "
                        + "HU id.",
                Map.of("huId", strProp("The handling-unit id (UUID) to trace.")),
                List.of("huId")));

        defs.add(tool("get_transport_tasks",
                "Recent transport / device tasks (conveyor, AMR, ASRS, etc.) for the active warehouse, "
                        + "optionally filtered by status. Use for questions about ongoing or recent moves.",
                Map.of(
                        "status", strProp("Optional task status to filter by, e.g. PENDING, DISPATCHED, COMPLETED, FAILED."),
                        "limit", intProp("Optional max number of tasks to return (default 20, max 100).")),
                List.of()));

        defs.add(tool("get_stock_blocking",
                "What is blocking outbound allocation in the active warehouse (e.g. short stock, holds). "
                        + "Use for 'why can't this order ship' / 'what's blocking outbound' questions.",
                Map.of(), List.of()));

        defs.add(tool("get_dashboard",
                "Order-management headline for the active warehouse: inbound and outbound order figures. "
                        + "Use for overall throughput / today's order picture questions.",
                Map.of(), List.of()));

        return defs;
    }

    private static Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> props.putAdditionalProperty(k, JsonValue.from(v)));
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(props.build());
        if (!required.isEmpty()) {
            schema.required(required);
        }
        return Tool.builder().name(name).description(description).inputSchema(schema.build()).build();
    }

    private static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> intProp(String description) {
        return Map.of("type", "integer", "description", description);
    }
}
