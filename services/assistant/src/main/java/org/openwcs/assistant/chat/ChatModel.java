package org.openwcs.assistant.chat;

import java.util.List;

/**
 * Abstraction over the LLM interaction so the controller/service is decoupled from the Anthropic SDK
 * and the interaction can be mocked in tests (no real Anthropic calls). The implementation runs the
 * tool-use agentic loop, executing each tool the model requests against the warehouse services, and
 * returns the model's final natural-language answer.
 */
public interface ChatModel {

    /**
     * Run a chat turn.
     *
     * @param apiKey       the Anthropic API key (from master-data config; never logged)
     * @param model        the Claude model id (e.g. {@code claude-haiku-4-5} / {@code claude-opus-4-8})
     * @param systemPrompt the system prompt (includes the active warehouseId)
     * @param messages     the conversation so far (user/assistant turns)
     * @return the model's final answer text
     */
    String chat(String apiKey, String model, String systemPrompt, List<ChatMessage> messages);
}
