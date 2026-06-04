package org.openwcs.iam.api;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request bodies for the IAM API. */
public final class Requests {

    private Requests() {
    }

    /** Set a user's allowed warehouses and (optionally) their default. Default must be in the list. */
    public record SetWarehouseAccess(List<UUID> warehouses, UUID defaultWarehouse) {
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
