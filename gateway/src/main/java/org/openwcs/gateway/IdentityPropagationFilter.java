package org.openwcs.gateway;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Propagates the authenticated identity to downstream services as trusted headers
 * ({@code X-Auth-User}, {@code X-Auth-Roles}) derived from the validated JWT, and ALWAYS
 * strips any client-supplied versions of those headers so they cannot be spoofed (the
 * gateway is the trust boundary; internal hops are secured by mTLS, build.md §12). With
 * security disabled there is no principal, so the headers are simply stripped.
 */
@Component
public class IdentityPropagationFilter implements GlobalFilter, Ordered {

    static final String USER_HEADER = "X-Auth-User";
    static final String ROLES_HEADER = "X-Auth-Roles";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(USER_HEADER);
                    h.remove(ROLES_HEADER);
                })
                .build();

        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(auth -> auth != null && auth.getPrincipal() instanceof Jwt)
                .map(auth -> (Jwt) auth.getPrincipal())
                .map(jwt -> exchange.mutate().request(withIdentity(stripped, jwt)).build())
                .defaultIfEmpty(exchange.mutate().request(stripped).build())
                .flatMap(chain::filter);
    }

    private ServerHttpRequest withIdentity(ServerHttpRequest stripped, Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username == null) {
            username = jwt.getSubject();
        }
        String roles = String.join(",", realmRoles(jwt));
        String user = username;
        return stripped.mutate()
                .headers(h -> {
                    if (user != null) {
                        h.set(USER_HEADER, user);
                    }
                    if (!roles.isEmpty()) {
                        h.set(ROLES_HEADER, roles);
                    }
                })
                .build();
    }

    @SuppressWarnings("unchecked")
    private Collection<String> realmRoles(Jwt jwt) {
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
