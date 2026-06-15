package org.openwcs.notification.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-endpoint RBAC for the alerts API (build.md §4.8). Reads (GET /api/notification/alerts) require
 * a view permission (INVENTORY_VIEW, held by every shipped role). Acknowledgement
 * (POST .../ack) is restricted to SUPERVISOR / ADMIN. Gated by {@code openwcs.security.enabled} so
 * the stack runs before a Keycloak realm.
 */
@Component
public class RbacFilter extends OncePerRequestFilter {

    private static final List<String> ACK_ROLES = List.of("SUPERVISOR", "ADMIN");

    private final boolean enabled;

    public RbacFilter(@Value("${openwcs.security.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!enabled || !uri.startsWith("/api/notification")) {
            chain.doFilter(request, response);
            return;
        }
        List<String> roles = AccessControl.parseRoles(request.getHeader("X-Auth-Roles"));

        if (HttpMethod.GET.matches(request.getMethod())) {
            if (!AccessControl.granted(roles, Permission.INVENTORY_VIEW)) {
                forbidden(response, "Missing permission INVENTORY_VIEW");
                return;
            }
        } else {
            // Mutations (ack) require a supervisor/admin role.
            if (roles.stream().noneMatch(ACK_ROLES::contains)) {
                forbidden(response, "Requires SUPERVISOR or ADMIN");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void forbidden(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"" + detail + "\"}");
    }
}
