package org.openwcs.common.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Pure authorization helpers used by services to check a request's forwarded roles
 * (the gateway's {@code X-Auth-Roles} header) against a required coded permission.
 */
public final class AccessControl {

    private AccessControl() {
    }

    /** Parse the comma-separated {@code X-Auth-Roles} header into role names. */
    public static List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return List.of();
        }
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** True if any of the given roles grants the permission. */
    public static boolean granted(Collection<String> roleNames, Permission permission) {
        return RoleCatalog.permissionsFor(roleNames).contains(permission);
    }
}
