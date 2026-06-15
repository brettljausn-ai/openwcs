package org.openwcs.assistant.config;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Fetches the {@link AiConfig} (Anthropic key + model + enabled flag) from master-data's network-only
 * internal endpoint ({@code GET {masterDataBaseUrl}/internal/ai-assistant}) and caches it briefly
 * (default 60s) so a chat burst doesn't hammer master-data. If the fetch fails, the assistant is
 * treated as not configured (a disabled {@link AiConfig}) rather than throwing — the chat endpoint
 * then surfaces a clean "AI assistant not configured" error. The key is never logged.
 */
@Component
public class AiConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(AiConfigProvider.class);
    private static final AiConfig DISABLED = new AiConfig(null, null, false);

    private final RestClient masterData;
    private final long cacheMs;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public AiConfigProvider(
            RestClient.Builder builder,
            @Value("${openwcs.assistant.master-data-base-url:http://localhost:8081}") String masterDataUrl,
            @Value("${openwcs.assistant.config-cache-ms:60000}") long cacheMs) {
        this.masterData = builder.clone().baseUrl(masterDataUrl).build();
        this.cacheMs = cacheMs;
    }

    /** The current AI config, served from the short-lived cache or freshly fetched. Never null. */
    public AiConfig current() {
        Cached cached = cache.get();
        long now = System.currentTimeMillis();
        if (cached != null && (now - cached.fetchedAt) < cacheMs) {
            return cached.config;
        }
        AiConfig fresh = fetch();
        cache.set(new Cached(fresh, now));
        return fresh;
    }

    private AiConfig fetch() {
        try {
            AiConfig config = masterData.get()
                    .uri("/internal/ai-assistant")
                    .retrieve()
                    .body(AiConfig.class);
            return config != null ? config : DISABLED;
        } catch (Exception e) {
            // Do not include the response body in the log — it carries the key.
            log.warn("could not fetch AI assistant config from master-data: {}", e.getClass().getSimpleName());
            return DISABLED;
        }
    }

    private record Cached(AiConfig config, long fetchedAt) {
    }
}
