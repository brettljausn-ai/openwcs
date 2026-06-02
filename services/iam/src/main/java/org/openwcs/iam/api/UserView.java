package org.openwcs.iam.api;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.iam.domain.AppUser;
import org.openwcs.iam.domain.Role;

/** Read model for a user, including assigned roles and the resulting effective permissions. */
public record UserView(
        UUID id,
        String username,
        String displayName,
        String externalId,
        String status,
        Set<String> roles,
        Set<String> permissions) {

    public static UserView from(AppUser u) {
        Set<String> roleNames = u.getRoles().stream().map(Role::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> permissions = u.getRoles().stream()
                .flatMap(r -> r.getPermissions().stream())
                .collect(Collectors.toCollection(TreeSet::new));
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.getExternalId(),
                u.getStatus(), roleNames, permissions);
    }
}
