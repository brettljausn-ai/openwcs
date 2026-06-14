package org.openwcs.gateway;

import java.util.List;
import java.util.Map;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Server-side mirror of the parts of the UI screen catalog (ui/src/auth/screens.ts) the gateway
 * needs to enforce read-vs-write screen access (build.md §4.8): which API write paths a screen
 * owns, and each screen's built-in default access level per role.
 *
 * <p>Only writes ({@code POST/PUT/PATCH/DELETE}) to a <em>mapped</em> path are checked; reads
 * (GET) always pass, and a write to an <em>unmapped</em> path is allowed (fail-open) — coverage is
 * deliberately limited to screens whose write API surface is unambiguously owned by a single
 * screen, and expands over time. Screens whose writes are shared or out-of-band (orders share
 * {@code /api/orders}; user management uses the Keycloak admin API off {@code /api/**}; settings
 * fans writes across many services) are intentionally not mapped here and rely on the UI's
 * write-gating instead.
 */
@Component
public class ScreenWriteCatalog {

    enum Level { READ, WRITE }

    /** A write path owned by one screen. */
    record Rule(String screenKey, PathPattern pattern) {
    }

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    private static Rule rule(String screenKey, String pattern) {
        return new Rule(screenKey, PARSER.parse(pattern));
    }

    // Write paths, checked in order; the first match wins. Each `/**` also matches the bare prefix.
    private final List<Rule> rules = List.of(
            rule("master-data:warehouses", "/api/master-data/warehouses/**"),
            rule("master-data:skus", "/api/master-data/skus/**"),
            rule("master-data:storage-blocks", "/api/master-data/storage-blocks/**"),
            rule("master-data:locations", "/api/master-data/locations/**"),
            rule("master-data:equipment", "/api/master-data/equipment/**"),
            rule("master-data:handling-unit-types", "/api/master-data/handling-unit-types/**"),
            rule("counting", "/api/counting/**"),
            rule("slotting", "/api/slotting/**"),
            rule("topology", "/api/flow/automation/topology/**"),
            rule("topology", "/api/flow/conveyor/topology/**"),
            rule("warehouse-access", "/api/iam/warehouse-access/**"),
            rule("access-control", "/api/iam/screen-access/**"));

    // Access-required paths: reachable on ANY method only if the user has at least READ on the
    // owning screen (not just writes). Used for screens whose API surface is privileged enough that
    // mere reachability is gated by screen access rather than a hard role check. The admin database
    // console is SELECT-only, so READ access is sufficient to use it (no separate write rule).
    private final List<Rule> accessRules = List.of(
            rule("admin-database", "/api/master-data/admin/db/**"));

    // Built-in default level per role for the mapped screens (mirror of defaultLevel() in
    // screens.ts: VIEWER → read, others → write, for roles in the screen's defaultRoles; roles not
    // listed have no default access). Master-data + engineering screens default ADMIN/SUPERVISOR
    // write; counting also OPERATOR; the admin screens ADMIN only.
    private static final Map<String, Level> MD_ENGINEERING = Map.of("ADMIN", Level.WRITE, "SUPERVISOR", Level.WRITE);
    private static final Map<String, Level> OPS_FULL =
            Map.of("ADMIN", Level.WRITE, "SUPERVISOR", Level.WRITE, "OPERATOR", Level.WRITE);
    private static final Map<String, Level> ADMIN_ONLY = Map.of("ADMIN", Level.WRITE);

    private final Map<String, Map<String, Level>> defaults = Map.ofEntries(
            Map.entry("master-data:warehouses", MD_ENGINEERING),
            Map.entry("master-data:skus", MD_ENGINEERING),
            Map.entry("master-data:storage-blocks", MD_ENGINEERING),
            Map.entry("master-data:locations", MD_ENGINEERING),
            Map.entry("master-data:equipment", MD_ENGINEERING),
            Map.entry("master-data:handling-unit-types", MD_ENGINEERING),
            Map.entry("counting", OPS_FULL),
            Map.entry("slotting", MD_ENGINEERING),
            Map.entry("topology", MD_ENGINEERING),
            Map.entry("warehouse-access", ADMIN_ONLY),
            Map.entry("access-control", ADMIN_ONLY),
            Map.entry("admin-database", ADMIN_ONLY));

    /** The screen that owns this write path, or null if none (→ unenforced, fail-open). */
    String screenForPath(String path) {
        return match(rules, path);
    }

    /**
     * The screen whose access governs this path on ANY method (read or write), or null if none.
     * A user must have at least READ on that screen to reach the path at all.
     */
    String screenForAccessPath(String path) {
        return match(accessRules, path);
    }

    private static String match(List<Rule> ruleSet, String path) {
        PathContainer container = PathContainer.parsePath(path);
        for (Rule r : ruleSet) {
            if (r.pattern().matches(container)) {
                return r.screenKey();
            }
        }
        return null;
    }

    /**
     * The user's effective access level on {@code screenKey}: their override (strongest of the
     * per-user entry and any matching role) if the screen is overridden, otherwise the built-in
     * defaults. {@code null} = OFF.
     */
    Level effectiveLevel(String screenKey, List<String> roles, String username,
            Map<String, ScreenAccessResolver.Override> overrides) {
        ScreenAccessResolver.Override o = overrides.get(screenKey);
        boolean overridden = o != null && (!o.roles().isEmpty() || !o.users().isEmpty());
        Level best = null;
        if (overridden) {
            if (username != null) {
                best = stronger(best, parse(o.users().get(username)));
            }
            for (String role : roles) {
                best = stronger(best, parse(o.roles().get(role)));
            }
        } else {
            Map<String, Level> d = defaults.get(screenKey);
            if (d != null) {
                for (String role : roles) {
                    best = stronger(best, d.get(role));
                }
            }
        }
        return best;
    }

    private static Level parse(String wire) {
        if (wire == null) {
            return null;
        }
        return switch (wire.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "write" -> Level.WRITE;
            case "read" -> Level.READ;
            default -> null;
        };
    }

    private static Level stronger(Level a, Level b) {
        if (a == Level.WRITE || b == Level.WRITE) {
            return Level.WRITE;
        }
        return a != null ? a : b;
    }
}
