package org.openwcs.processdesigner.assist;

/**
 * AI task-assist was invoked but no Anthropic key is configured ({@code ANTHROPIC_API_KEY} unset).
 * Maps to HTTP 503 with a clear "AI assist not configured" body. The Spring context still starts —
 * this is surfaced only on use, guarded by a ContextLoads-style test.
 */
public class AssistNotConfiguredException extends RuntimeException {
    public AssistNotConfiguredException(String message) {
        super(message);
    }
}
