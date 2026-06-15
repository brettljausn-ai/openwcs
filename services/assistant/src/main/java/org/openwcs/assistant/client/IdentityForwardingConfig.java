package org.openwcs.assistant.client;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Propagates the caller's authenticated identity ({@code X-Auth-User}/{@code X-Auth-Roles}/
 * {@code X-Auth-Warehouses}) onto outbound inter-service calls so downstream RBAC + warehouse scope
 * see the original user. Applied to the {@link org.springframework.web.client.RestClient} used by the
 * tool HTTP client. The config fetch to master-data's internal endpoint runs with the same builder,
 * which is harmless: that endpoint is network-only and not identity-gated.
 */
@Configuration
public class IdentityForwardingConfig {

    static final String[] HEADERS = {"X-Auth-User", "X-Auth-Roles", "X-Auth-Warehouses"};

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
