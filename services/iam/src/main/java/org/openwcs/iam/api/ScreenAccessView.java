package org.openwcs.iam.api;

import java.util.Map;
import java.util.TreeMap;
import org.openwcs.iam.domain.AccessLevel;
import org.openwcs.iam.domain.ScreenAccess;

/**
 * The override for a single screen, as the value in the screen-access map
 * ({@code { "<screenKey>": { roles, users } }}) — matching the shape the UI's AuthContext
 * consumes (ui/src/auth/screens.ts {@code AccessOverrides}). {@code roles}/{@code users} map a
 * role / username to its access level ({@code "read"} or {@code "write"}); absence means OFF.
 */
public record ScreenAccessView(Map<String, String> roles, Map<String, String> users) {

    public static ScreenAccessView from(ScreenAccess entity) {
        return new ScreenAccessView(wire(entity.getRoles()), wire(entity.getUsers()));
    }

    private static Map<String, String> wire(Map<String, AccessLevel> levels) {
        Map<String, String> out = new TreeMap<>();
        levels.forEach((name, level) -> out.put(name, level.wire()));
        return out;
    }
}
