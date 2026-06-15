package org.openwcs.assistant.service;

/** Thrown when a chat is requested but the AI assistant is disabled or has no key. Surfaced as 409. */
public class NotConfiguredException extends RuntimeException {
    public NotConfiguredException(String message) {
        super(message);
    }
}
