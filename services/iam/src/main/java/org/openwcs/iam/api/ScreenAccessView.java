package org.openwcs.iam.api;

import java.util.List;
import java.util.Set;
import org.openwcs.iam.domain.ScreenAccess;

/**
 * The override for a single screen, as the value in the screen-access map
 * ({@code { "<screenKey>": { roles, users } }}) — matching the shape the UI's AuthContext
 * consumes (ui/src/auth/screens.ts {@code AccessOverrides}).
 */
public record ScreenAccessView(List<String> roles, List<String> users) {

    public static ScreenAccessView from(ScreenAccess entity) {
        return new ScreenAccessView(sorted(entity.getRoles()), sorted(entity.getUsers()));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
