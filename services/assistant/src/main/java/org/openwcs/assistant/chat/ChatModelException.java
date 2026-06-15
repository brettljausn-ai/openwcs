package org.openwcs.assistant.chat;

/** Thrown when the model interaction fails (e.g. an Anthropic API error). Surfaced as HTTP 502. */
public class ChatModelException extends RuntimeException {
    public ChatModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
