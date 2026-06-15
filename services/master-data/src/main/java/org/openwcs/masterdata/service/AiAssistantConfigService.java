package org.openwcs.masterdata.service;

import java.util.Set;
import org.openwcs.masterdata.domain.SystemConfiguration;
import org.openwcs.masterdata.repo.SystemConfigurationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI-assistant configuration: the Anthropic API key, model, and enabled flag, stored as global
 * key/value rows in {@code system_configuration} (mirroring demo mode, the hardware emulator, and
 * stock rules). Admin-managed; the API key is write-only and never leaves the service except via
 * the network-only {@code /internal/ai-assistant} endpoint the assistant service calls server-side.
 *
 * <p>The assistant feature is OFF by default and unconfigured (no key) until an admin sets it up.
 */
@Service
public class AiAssistantConfigService {

    static final String API_KEY = "AI_ASSISTANT_API_KEY";
    static final String MODEL = "AI_ASSISTANT_MODEL";
    static final String ENABLED = "AI_ASSISTANT_ENABLED";

    static final String DEFAULT_MODEL = "claude-haiku-4-5";

    /** The models an admin may select for the assistant. */
    public static final Set<String> ALLOWED_MODELS = Set.of("claude-haiku-4-5", "claude-opus-4-8");

    private final SystemConfigurationRepository config;

    public AiAssistantConfigService(SystemConfigurationRepository config) {
        this.config = config;
    }

    /** Whether {@code model} is one an admin may select. */
    public static boolean isAllowedModel(String model) {
        return ALLOWED_MODELS.contains(model);
    }

    /** Public view of the assistant config: never carries the API key. */
    @Transactional(readOnly = true)
    public PublicView publicView() {
        return new PublicView(enabled(), isConfigured(), model());
    }

    /**
     * Full view including the actual API key, for server-side callers (the assistant service) only.
     * Reached exclusively through the network-only {@code /internal/ai-assistant} endpoint.
     */
    @Transactional(readOnly = true)
    public InternalView internalView() {
        return new InternalView(apiKeyOrNull(), model(), enabled());
    }

    /**
     * Apply an admin update. Any field left {@code null} is unchanged. {@code apiKey}: a present,
     * non-blank value is stored trimmed; {@code null} leaves the stored key untouched (so an admin
     * can toggle model/enabled without re-entering the key); an explicit empty/blank string clears
     * the key. Returns the resulting public view.
     *
     * @throws IllegalArgumentException if {@code model} is non-null and not in {@link #ALLOWED_MODELS}
     */
    @Transactional
    public PublicView update(String apiKey, String model, Boolean enabled) {
        if (model != null) {
            if (!isAllowedModel(model)) {
                throw new IllegalArgumentException("Unknown AI assistant model: " + model);
            }
            put(MODEL, model);
        }
        if (enabled != null) {
            put(ENABLED, Boolean.toString(enabled));
        }
        if (apiKey != null) {
            // present but blank → clear; present and non-blank → store trimmed.
            put(API_KEY, apiKey.isBlank() ? "" : apiKey.trim());
        }
        return publicView();
    }

    private boolean enabled() {
        return config.findById(ENABLED).map(c -> Boolean.parseBoolean(c.getValue())).orElse(false);
    }

    private String model() {
        return config.findById(MODEL)
                .map(SystemConfiguration::getValue)
                .filter(v -> !v.isBlank())
                .orElse(DEFAULT_MODEL);
    }

    private String apiKeyOrNull() {
        return config.findById(API_KEY)
                .map(SystemConfiguration::getValue)
                .filter(v -> !v.isBlank())
                .orElse(null);
    }

    private boolean isConfigured() {
        return apiKeyOrNull() != null;
    }

    private void put(String key, String value) {
        SystemConfiguration c = config.findById(key).orElseGet(() -> new SystemConfiguration(key, value));
        c.setValue(value);
        config.save(c);
    }

    /** Public assistant config; the API key is intentionally absent. */
    public record PublicView(boolean enabled, boolean configured, String model) {
    }

    /** Server-side assistant config including the real API key (network-only). */
    public record InternalView(String apiKey, String model, boolean enabled) {
    }
}
