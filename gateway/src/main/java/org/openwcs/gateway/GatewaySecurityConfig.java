package org.openwcs.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Edge security (build.md §4.8, §12). When {@code openwcs.security.enabled=true} the gateway
 * is an OAuth2 resource server: it validates the JWT (issuer = Keycloak realm) and requires
 * authentication on everything except actuator. When disabled (default, for local dev before
 * a realm exists) all traffic is permitted so the stack runs without tokens.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            @Value("${openwcs.security.enabled:false}") boolean securityEnabled) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
        if (securityEnabled) {
            log.info("edge security ENABLED: JWT validation required on all routes except /actuator/**; identity is forwarded downstream as X-Auth-* headers");
            http.authorizeExchange(exchange -> exchange
                            .pathMatchers("/actuator/**").permitAll()
                            .anyExchange().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        } else {
            log.warn("edge security DISABLED (openwcs.security.enabled=false): all traffic is permitted without tokens; intended for local dev only");
            http.authorizeExchange(exchange -> exchange.anyExchange().permitAll());
        }
        return http.build();
    }
}
