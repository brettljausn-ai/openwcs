package org.openwcs.iam.api;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

/** Request bodies for the IAM API. */
public final class Requests {

    private Requests() {
    }

    public record CreateUser(@NotBlank String username, String displayName, String externalId) {
    }

    public record CreateRole(@NotBlank String name, String description, Set<String> permissions) {
    }

    public record SetRoles(Set<String> roleNames) {
    }

    public record SetPermissions(Set<String> permissions) {
    }
}
