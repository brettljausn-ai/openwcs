package org.openwcs.orders.client;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Propagates the caller's authenticated identity ({@code X-Auth-User}/{@code X-Auth-Roles})
 * onto outbound calls (order-management → allocation), so RBAC checks downstream see the
 * original user. Background calls without a request (e.g. the outbox relay → txlog) forward
 * nothing. Applies to every {@code RestClient} built from the auto-configured builder.
 */
@Configuration
public class IdentityForwardingConfig {

    private static final String[] HEADERS = {"X-Auth-User", "X-Auth-Roles"};

    @Bean
    public RestClientCustomizer identityForwardingCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest incoming = attrs.getRequest();
                for (String header : HEADERS) {
                    String value = incoming.getHeader(header);
                    if (value != null && !request.getHeaders().containsKey(header)) {
                        request.getHeaders().add(header, value);
                    }
                }
            }
            return execution.execute(request, body);
        });
    }
}
