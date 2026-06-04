package org.openwcs.common.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    /**
     * Warehouse-scope check for a warehouse ID carried in a request body (which the gateway
     * cannot enforce at the edge). The gateway forwards the caller's allowed warehouses as a
     * comma-separated {@code X-Auth-Warehouses} header:
     * <ul>
     *   <li><b>absent (null)</b> — unscoped: admin, an internal call without identity propagation,
     *       or security disabled → always allowed;</li>
     *   <li><b>present</b> (even empty) — a scoped user: the target must be in the set.</li>
     * </ul>
     * A null {@code warehouseId} means there is nothing to enforce (allowed).
     */
    public static boolean warehouseAllowed(String warehousesHeader, UUID warehouseId) {
        if (warehousesHeader == null || warehouseId == null) {
            return true;
        }
        return parseWarehouses(warehousesHeader).contains(warehouseId);
    }

    /** Parse the comma-separated {@code X-Auth-Warehouses} header into warehouse IDs (bad ones skipped). */
    public static Set<UUID> parseWarehouses(String warehousesHeader) {
        Set<UUID> out = new HashSet<>();
        if (warehousesHeader == null || warehousesHeader.isBlank()) {
            return out;
        }
        for (String part : warehousesHeader.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                try {
                    out.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed entries
                }
            }
        }
        return out;
    }
}
