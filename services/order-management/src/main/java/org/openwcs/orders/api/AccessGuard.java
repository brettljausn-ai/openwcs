package org.openwcs.orders.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.common.security.Permission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-endpoint RBAC check (build.md §4.8). Reads the gateway-forwarded {@code X-Auth-Roles}
 * and verifies the required coded permission. Enforcement is gated by
 * {@code openwcs.security.enabled}: off (default) it permits everything so the stack runs
 * before a Keycloak realm exists; on, a missing permission yields 403.
 */
@Component
public class AccessGuard {

    private final boolean enabled;

    public AccessGuard(@Value("${openwcs.security.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void require(String rolesHeader, Permission permission) {
        if (!enabled) {
            return;
        }
        if (!AccessControl.granted(AccessControl.parseRoles(rolesHeader), permission)) {
            throw new ForbiddenException(permission);
        }
    }
}
