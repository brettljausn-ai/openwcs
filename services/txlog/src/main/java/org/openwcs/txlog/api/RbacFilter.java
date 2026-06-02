package org.openwcs.txlog.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-endpoint RBAC for txlog (build.md §4.8). Query/replay (GET) requires TXLOG_VIEW.
 * Appending events (POST) is left unenforced here: it is internal infrastructure driven by
 * other services' relays (which carry no user context) and is authorized upstream at the
 * action that produced the event. Gated by {@code openwcs.security.enabled}.
 */
@Component
public class RbacFilter extends OncePerRequestFilter {

    private final boolean enabled;

    public RbacFilter(@Value("${openwcs.security.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Permission required = requiredPermission(request);
        if (required != null
                && !AccessControl.granted(AccessControl.parseRoles(request.getHeader("X-Auth-Roles")), required)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/problem+json");
            response.getWriter().write(
                    "{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"Missing permission " + required + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private Permission requiredPermission(HttpServletRequest request) {
        if (!enabled || !request.getRequestURI().startsWith("/api/txlog")) {
            return null;
        }
        // Only reads are user-RBAC enforced; append is internal (relay-driven).
        return HttpMethod.GET.matches(request.getMethod()) ? Permission.TXLOG_VIEW : null;
    }
}
