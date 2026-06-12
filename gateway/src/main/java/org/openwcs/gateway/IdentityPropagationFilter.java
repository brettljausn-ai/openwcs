package org.openwcs.gateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Propagates the authenticated identity to downstream services as trusted headers
 * ({@code X-Auth-User}, {@code X-Auth-Roles}, {@code X-Auth-Warehouses}) derived from the
 * validated JWT, and ALWAYS strips any client-supplied versions so they cannot be spoofed (the
 * gateway is the trust boundary; build.md §12). With security disabled there is no principal, so
 * the headers are simply stripped.
 *
 * <p>It also enforces warehouse scope (build.md §4.8): a non-admin request that names a
 * {@code warehouseId} (query parameter, or the {@code /warehouses/{id}} master-data path) outside
 * the user's allowed set is rejected with 403. Admins are never scoped. Writes that carry the
 * warehouse only in a JSON body are out of scope here and are guarded per-endpoint downstream via
 * the forwarded {@code X-Auth-Warehouses} header (follow-up).
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdentityPropagationFilter.class);

    static final String USER_HEADER = "X-Auth-User";
    static final String ROLES_HEADER = "X-Auth-Roles";
    static final String WAREHOUSES_HEADER = "X-Auth-Warehouses";

    private final WarehouseAccessResolver warehouses;

    public IdentityPropagationFilter(WarehouseAccessResolver warehouses) {
        this.warehouses = warehouses;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(USER_HEADER);
                    h.remove(ROLES_HEADER);
                    h.remove(WAREHOUSES_HEADER);
                })
                .build();

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.getPrincipal() instanceof Jwt)
                .map(auth -> (Jwt) auth.getPrincipal())
                .flatMap(jwt -> authenticated(exchange, chain, stripped, jwt))
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange.mutate().request(stripped).build())));
    }

    private Mono<Void> authenticated(
            ServerWebExchange exchange, GatewayFilterChain chain, ServerHttpRequest stripped, Jwt jwt) {
        String name = jwt.getClaimAsString("preferred_username");
        final String username = name != null ? name : jwt.getSubject();
        final List<String> roles = realmRoles(jwt);

        // Admins are never warehouse-scoped — forward identity, no resolution/enforcement.
        if (roles.contains("ADMIN")) {
            return forward(exchange, chain, withIdentity(stripped, username, roles, null));
        }

        return warehouses.allowedFor(username).flatMap(allowed -> {
            if (allowed.isEmpty()) {
                // Could not resolve (IAM unavailable) — fail open, forward without a warehouse header.
                return forward(exchange, chain, withIdentity(stripped, username, roles, null));
            }
            Set<UUID> set = allowed.get();
            UUID requested = requestedWarehouse(exchange.getRequest());
            if (requested != null && !set.contains(requested)) {
                log.warn("warehouse-scope denied: user {} requested warehouse {} which is outside their {} allowed warehouse(s); returning 403",
                        username, requested, set.size());
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            return forward(exchange, chain, withIdentity(stripped, username, roles, set));
        });
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, ServerHttpRequest request) {
        return chain.filter(exchange.mutate().request(request).build());
    }

    private ServerHttpRequest withIdentity(ServerHttpRequest stripped, String user, List<String> roles, Set<UUID> allowed) {
        String rolesStr = String.join(",", roles);
        String warehousesStr = allowed == null ? null
                : allowed.stream().map(UUID::toString).collect(Collectors.joining(","));
        return stripped.mutate()
                .headers(h -> {
                    if (user != null) {
                        h.set(USER_HEADER, user);
                    }
                    if (!rolesStr.isEmpty()) {
                        h.set(ROLES_HEADER, rolesStr);
                    }
                    if (warehousesStr != null) {
                        h.set(WAREHOUSES_HEADER, warehousesStr);
                    }
                })
                .build();
    }

    /** The warehouse a request targets: {@code ?warehouseId=} or {@code /warehouses/{id}}; null if none. */
    private static UUID requestedWarehouse(ServerHttpRequest request) {
        UUID fromQuery = parseUuid(request.getQueryParams().getFirst("warehouseId"));
        if (fromQuery != null) {
            return fromQuery;
        }
        String[] segments = request.getPath().value().split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if ("warehouses".equals(segments[i])) {
                return parseUuid(segments[i + 1]);
            }
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> roles) {
            return roles.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    @Override
    public int getOrder() {
        // The security context is populated by Spring Security's WebFilter chain, which runs
        // before any gateway GlobalFilter — so we only need to ensure we run *before* the
        // routing filter (NettyRoutingFilter, LOWEST_PRECEDENCE), which forwards the request
        // downstream. Run one step ahead of it so the identity headers are in place first.
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
