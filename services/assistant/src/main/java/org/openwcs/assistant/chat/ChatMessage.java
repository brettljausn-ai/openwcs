package org.openwcs.assistant.chat;

/**
 * One turn in the conversation passed to the model. {@code role} is {@code "user"} or
 * {@code "assistant"}.
 */
public record ChatMessage(String role, String content) {
}
