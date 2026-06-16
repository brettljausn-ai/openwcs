package org.openwcs.processdesigner.api;

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
 * Per-endpoint RBAC for the process designer (spec §11). Reading definitions and the operator
 * process menu, plus starting / checkpointing / resuming instances, require
 * {@link Permission#PROCESS_DESIGN_VIEW} (granted to every shipped role). Authoring and publishing
 * definitions (the write side of {@code /defs}) require {@link Permission#PROCESS_DESIGN_EDIT}
 * (supervisor/admin). Gated by {@code openwcs.security.enabled}. Note that the curated task a
 * checkpoint runs additionally enforces the operator's identity at the target service.
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
        if (!enabled || !uri.startsWith("/api/process-designer")) {
            return null;
        }
        // Definition writes (create draft / edit draft / publish / archive) need the edit permission.
        boolean defWrite = uri.startsWith("/api/process-designer/defs")
                && !HttpMethod.GET.matches(request.getMethod());
        // AI task-assist (spec §7.3) is a design-time authoring aid -> edit permission.
        boolean assist = uri.startsWith("/api/process-designer/assist");
        return defWrite || assist ? Permission.PROCESS_DESIGN_EDIT : Permission.PROCESS_DESIGN_VIEW;
    }
}
