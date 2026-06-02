package org.openwcs.inventory.api;

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
 * Per-endpoint RBAC for inventory (build.md §4.8). Reads (stock / availability) require
 * INVENTORY_VIEW; reservation writes require ALLOCATION_RUN (reservations are the allocation
 * primitive). The caller's roles are forwarded as {@code X-Auth-Roles} — for the
 * allocation→inventory hop, the original user's roles are propagated by the caller. Gated by
 * {@code openwcs.security.enabled}.
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
        if (!enabled || !request.getRequestURI().startsWith("/api/inventory")) {
            return null;
        }
        return HttpMethod.GET.matches(request.getMethod())
                ? Permission.INVENTORY_VIEW
                : Permission.ALLOCATION_RUN;
    }
}
