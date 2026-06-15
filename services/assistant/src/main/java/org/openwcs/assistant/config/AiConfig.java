package org.openwcs.assistant.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The AI-assistant configuration owned by master-data: the Anthropic API key, the model string, and
 * the enabled flag. Fetched server-side from master-data's network-only internal endpoint. The
 * {@code apiKey} is never logged or returned to clients — {@link #toString()} redacts it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiConfig(String apiKey, String model, boolean enabled) {

    /** True when the assistant is usable: explicitly enabled and a non-blank key is configured. */
    public boolean usable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Whether a key is present (without revealing it) — used by the public status endpoint. */
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "AiConfig{enabled=" + enabled + ", model=" + model + ", apiKey=***}";
    }
}
