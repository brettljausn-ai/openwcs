package org.openwcs.gateway;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Resolves the full per-screen access override map from the IAM service (its network-only
 * {@code /internal/screen-access} endpoint) for the gateway's read-vs-write screen enforcement.
 * The map is small (only overridden screens) and shared across users, so it is fetched once and
 * cached briefly rather than per request.
 *
 * <p>Resolution failures (IAM down/slow) return {@link Optional#empty()} — the caller then
 * <em>skips</em> enforcement (fail-open) rather than blocking writes when IAM blips. The mapping
 * store is the source of truth; this is a guard, not the primary control.
 */
@Component
public class ScreenAccessResolver {

    private static final Logger log = LoggerFactory.getLogger(ScreenAccessResolver.class);
    private static final long TTL_MILLIS = 30_000;
    private static final ParameterizedTypeReference<Map<String, Override>> TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient client;
    private volatile Cached cache;

    public ScreenAccessResolver(
            WebClient.Builder builder,
            @Value("${openwcs.uri.iam:http://localhost:8087}") String iamBaseUrl) {
        this.client = builder.baseUrl(iamBaseUrl).build();
    }

    /**
     * The full override map (keyed by screen key). {@code Optional.empty()} means "could not
     * resolve" (skip enforcement); a present (possibly empty) map is authoritative.
     */
    public Mono<Optional<Map<String, Override>>> overrides() {
        Cached hit = cache;
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            return Mono.just(Optional.of(hit.map));
        }
        return client.get()
                .uri("/internal/screen-access")
                .retrieve()
                .bodyToMono(TYPE)
                .map(map -> {
                    Map<String, Override> resolved = map == null ? Map.of() : map;
                    cache = new Cached(resolved, now + TTL_MILLIS);
                    return Optional.of(resolved);
                })
                .onErrorResume(e -> {
                    log.warn("Screen-access resolution failed ({}); skipping write enforcement", e.toString());
                    return Mono.just(Optional.<Map<String, Override>>empty());
                });
    }

    /** A single screen's override: role/username → level ({@code "read"}/{@code "write"}). */
    public record Override(Map<String, String> roles, Map<String, String> users) {
        public Override {
            roles = roles == null ? Map.of() : roles;
            users = users == null ? Map.of() : users;
        }
    }

    private record Cached(Map<String, Override> map, long expiresAt) {
    }
}
