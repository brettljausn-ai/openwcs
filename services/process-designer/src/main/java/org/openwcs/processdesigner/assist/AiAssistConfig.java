package org.openwcs.processdesigner.assist;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Design-time AI task-assist config (spec §7.3). The Anthropic API key comes from the
 * {@code ANTHROPIC_API_KEY} environment variable (same convention as services/assistant); the model
 * defaults to {@code claude-haiku-4-5} and is overridable in application.yml. When no key is present
 * the assist endpoint returns 503 (and the Spring context still starts — the bean resolves a null
 * key gracefully, guarded by a ContextLoads-style test). The key is never logged or returned.
 */
@Component
public class AiAssistConfig {

    private final String apiKey;
    private final String model;

    public AiAssistConfig(
            @Value("${openwcs.process-designer.assist.api-key:${ANTHROPIC_API_KEY:}}") String apiKey,
            @Value("${openwcs.process-designer.assist.model:claude-haiku-4-5}") String model) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.model = model == null || model.isBlank() ? "claude-haiku-4-5" : model;
    }

    /** Whether AI assist is usable (a non-blank Anthropic key is configured). */
    public boolean configured() {
        return apiKey != null;
    }

    /** The Anthropic API key, or null if unconfigured. Never logged or returned to clients. */
    public String apiKey() {
        return apiKey;
    }

    /** The Claude model id to use, e.g. {@code claude-haiku-4-5}. */
    public String model() {
        return model;
    }
}
