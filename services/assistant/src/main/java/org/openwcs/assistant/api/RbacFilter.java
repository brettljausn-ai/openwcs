package org.openwcs.assistant.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-endpoint RBAC for the assistant API (build.md §4.8). The chat endpoint (POST /api/assistant/chat)
 * requires a view permission (INVENTORY_VIEW, held by every shipped role) — it reads warehouse data on
 * the caller's behalf, so any authenticated operator may use it. The status endpoint
 * (GET /api/assistant/status) is reachable by any authenticated user (the gateway already enforces
 * authentication when security is enabled). Gated by {@code openwcs.security.enabled} so the stack runs
 * before a Keycloak realm.
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
        String uri = request.getRequestURI();
        if (!enabled || !uri.startsWith("/api/assistant")) {
            chain.doFilter(request, response);
            return;
        }
        // Status is allowed for any authenticated caller (the gateway proved authentication).
        if (uri.startsWith("/api/assistant/status")) {
            chain.doFilter(request, response);
            return;
        }
        // Chat: a view permission is required.
        List<String> roles = AccessControl.parseRoles(request.getHeader("X-Auth-Roles"));
        if (!AccessControl.granted(roles, Permission.INVENTORY_VIEW)) {
            forbidden(response, "Missing permission INVENTORY_VIEW");
            return;
        }
        chain.doFilter(request, response);
    }

    private void forbidden(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"title\":\"Forbidden\",\"status\":403,\"detail\":\"" + detail + "\"}");
    }
}
