package org.openwcs.common.security;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The shipped role → permission mapping (build.md §4.8). Mirrors the seed roles created by
 * the IAM service so that services can authorize a request from its forwarded roles without
 * a per-request IAM lookup. Custom roles created at runtime in IAM are NOT reflected here —
 * a service that needs them would resolve effective permissions from the IAM API (follow-up).
 */
public final class RoleCatalog {

    private static final Set<Permission> VIEWER = EnumSet.of(
            Permission.MASTER_DATA_VIEW, Permission.INVENTORY_VIEW,
            Permission.ORDER_VIEW, Permission.TXLOG_VIEW);

    private static final Set<Permission> OPERATOR = EnumSet.of(
            Permission.MASTER_DATA_VIEW, Permission.INVENTORY_VIEW,
            Permission.ORDER_VIEW, Permission.TXLOG_VIEW,
            Permission.ORDER_POST_TRANSACTION, Permission.STOCK_ADJUST);

    private static final Set<Permission> SUPERVISOR = EnumSet.complementOf(
            EnumSet.of(Permission.IAM_ADMIN));

    private static final Set<Permission> ADMIN = EnumSet.allOf(Permission.class);

    private static final Map<String, Set<Permission>> ROLES = Map.of(
            "VIEWER", VIEWER,
            "OPERATOR", OPERATOR,
            "SUPERVISOR", SUPERVISOR,
            "ADMIN", ADMIN);

    private RoleCatalog() {
    }

    /** Permissions of a single role (empty if the role is unknown to the shipped catalog). */
    public static Set<Permission> permissionsOf(String roleName) {
        return ROLES.getOrDefault(roleName, Set.of());
    }

    /** Union of permissions across the given roles. */
    public static Set<Permission> permissionsFor(Collection<String> roleNames) {
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        if (roleNames != null) {
            for (String role : roleNames) {
                result.addAll(permissionsOf(role));
            }
        }
        return result;
    }
}
