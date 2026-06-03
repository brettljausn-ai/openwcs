package org.openwcs.counting.api;

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
 * Per-endpoint RBAC for counting (build.md §4.8). Reads require INVENTORY_VIEW; mutations (creating
 * /scheduling tasks, submitting counts, reconciling — which can post a stock adjustment) require
 * STOCK_ADJUST. Gated by {@code openwcs.security.enabled}.
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
        String uri = request.getRequestURI();
        if (!enabled || !uri.startsWith("/api/counting")) {
            return null;
        }
        if (HttpMethod.GET.matches(request.getMethod())) {
            return Permission.INVENTORY_VIEW;
        }
        return Permission.STOCK_ADJUST;
    }
}
