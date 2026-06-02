package org.openwcs.iam.api;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.openwcs.iam.domain.Role;

/** Read model for a role. */
public record RoleView(UUID id, String name, String description, boolean system, Set<String> permissions) {

    public static RoleView from(Role r) {
        return new RoleView(r.getId(), r.getName(), r.getDescription(), r.isSystem(),
                new TreeSet<>(r.getPermissions()));
    }
}
