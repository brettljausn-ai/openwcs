package org.openwcs.assistant.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.assistant.chat.AssistantContext;
import org.openwcs.assistant.chat.ChatMessage;
import org.openwcs.assistant.chat.ChatModel;
import org.openwcs.assistant.config.AiConfig;
import org.openwcs.assistant.config.AiConfigProvider;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a chat turn: fetches the AI config from master-data, rejects when the assistant is not
 * configured/enabled, builds the warehouse-scoped system prompt, and delegates the tool-use loop to
 * the {@link ChatModel}. The Anthropic key never leaves this layer (handed straight to the model,
 * never logged or returned).
 */
@Service
public class AssistantService {

    private final AiConfigProvider configProvider;
    private final ChatModel chatModel;

    public AssistantService(AiConfigProvider configProvider, ChatModel chatModel) {
        this.configProvider = configProvider;
        this.chatModel = chatModel;
    }

    /** Public status (no key): whether the assistant is enabled + configured, and the active model. */
    public StatusView status() {
        AiConfig config = configProvider.current();
        return new StatusView(config.enabled(), config.configured(), config.model());
    }

    /**
     * Run a chat turn for {@code warehouseId} over the given conversation. Throws
     * {@link NotConfiguredException} when the assistant is disabled or has no key.
     */
    public String chat(UUID warehouseId, List<ChatMessage> messages) {
        AiConfig config = configProvider.current();
        if (!config.usable()) {
            throw new NotConfiguredException("AI assistant not configured");
        }
        String model = (config.model() == null || config.model().isBlank())
                ? "claude-haiku-4-5" : config.model();

        AssistantContext.setWarehouseId(warehouseId.toString());
        try {
            return chatModel.chat(config.apiKey(), model, systemPrompt(warehouseId), messages);
        } finally {
            AssistantContext.clear();
        }
    }

    private String systemPrompt(UUID warehouseId) {
        return """
            You are the openWCS warehouse assistant. You help warehouse operators ask, in plain \
            language, about orders, transports, stock, handling units, and dashboards in THIS openWCS \
            instance.

            The active warehouse for this conversation is %s. All tools are already scoped to it; \
            you only supply item-level parameters (a SKU, a handling-unit id, a status filter).

            Rules:
            - Answer ONLY from the tool results. Do not invent orders, quantities, ids, or statuses.
            - If a tool returns no data or an error, say the data is unavailable rather than guessing.
            - Be concise and operational: lead with the answer, then any short supporting detail.
            - Use the tools whenever the question depends on live warehouse data.
            """.formatted(warehouseId);
    }

    /** Public status shape (no key). */
    public record StatusView(boolean enabled, boolean configured, String model) {
    }
}
