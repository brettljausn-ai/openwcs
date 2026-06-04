package org.openwcs.gateway;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Resolves a user's allowed warehouse IDs from the IAM service (its network-only
 * {@code /internal/warehouse-access/{username}} endpoint) for the gateway's warehouse-scope
 * enforcement. Results are cached briefly so a user doesn't trigger an IAM call on every request.
 *
 * <p>Resolution failures (IAM down/slow) return {@link Optional#empty()} — the caller then
 * <em>skips</em> enforcement (fail-open) rather than locking everyone out of warehouse data when
 * IAM blips. The mapping store is the source of truth; this is a guard, not the primary control.
 */
@Component
public class WarehouseAccessResolver {

    private static final Logger log = LoggerFactory.getLogger(WarehouseAccessResolver.class);
    private static final long TTL_MILLIS = 30_000;

    private final WebClient client;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public WarehouseAccessResolver(
            WebClient.Builder builder,
            @Value("${openwcs.uri.iam:http://localhost:8087}") String iamBaseUrl) {
        this.client = builder.baseUrl(iamBaseUrl).build();
    }

    /**
     * The warehouses {@code username} may work in. {@code Optional.empty()} means "could not
     * resolve" (skip enforcement); a present (possibly empty) set is authoritative.
     */
    public Mono<Optional<Set<UUID>>> allowedFor(String username) {
        Cached hit = cache.get(username);
        long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            return Mono.just(Optional.of(hit.warehouses));
        }
        return client.get()
                .uri("/internal/warehouse-access/{username}", username)
                .retrieve()
                .bodyToMono(Response.class)
                .map(resp -> {
                    Set<UUID> set = new HashSet<>(resp.warehouses() == null ? List.of() : resp.warehouses());
                    cache.put(username, new Cached(set, now + TTL_MILLIS));
                    return Optional.of(set);
                })
                .onErrorResume(e -> {
                    log.warn("Warehouse-scope resolution failed for user {} ({}); skipping enforcement",
                            username, e.toString());
                    return Mono.just(Optional.<Set<UUID>>empty());
                });
    }

    /** The JSON shape returned by IAM's internal warehouse-access endpoint. */
    record Response(List<UUID> warehouses, UUID defaultWarehouse) {
    }

    private record Cached(Set<UUID> warehouses, long expiresAt) {
    }
}
