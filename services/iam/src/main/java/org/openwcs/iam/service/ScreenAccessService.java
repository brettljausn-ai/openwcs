package org.openwcs.iam.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.openwcs.iam.api.ScreenAccessView;
import org.openwcs.iam.domain.AccessLevel;
import org.openwcs.iam.domain.ScreenAccess;
import org.openwcs.iam.repo.ScreenAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configurable per-screen access (build.md §4.8). The UI owns the canonical screen catalog
 * (ui/src/auth/screens.ts) and each screen's default access; this service stores only the
 * *overrides* that replace those defaults. Screen keys are opaque UI-owned strings — whatever
 * is sent is persisted. Only overridden screens have entries.
 *
 * <p>Each role / user in an override carries an {@link AccessLevel} (READ or WRITE); an absent
 * role/user is OFF for that overridden screen.
 */
@Service
public class ScreenAccessService {

    private static final Logger log = LoggerFactory.getLogger(ScreenAccessService.class);

    private final ScreenAccessRepository repository;

    public ScreenAccessService(ScreenAccessRepository repository) {
        this.repository = repository;
    }

    /** The full override map, keyed by screen key — the shape the UI's AuthContext consumes. */
    @Transactional(readOnly = true)
    public Map<String, ScreenAccessView> overrides() {
        Map<String, ScreenAccessView> map = new TreeMap<>();
        for (ScreenAccess entry : repository.findAll()) {
            map.put(entry.getScreenKey(), ScreenAccessView.from(entry));
        }
        return map;
    }

    /**
     * Replace the entire override map. Screens absent from {@code incoming} lose their override
     * (back to UI defaults); empty entries (no roles and no users) are dropped rather than stored.
     */
    @Transactional
    public Map<String, ScreenAccessView> replaceAll(Map<String, ScreenAccessView> incoming) {
        Map<String, ScreenAccess> existing = new LinkedHashMap<>();
        for (ScreenAccess entry : repository.findAll()) {
            existing.put(entry.getScreenKey(), entry);
        }

        Set<String> kept = new java.util.HashSet<>();
        if (incoming != null) {
            for (Map.Entry<String, ScreenAccessView> e : incoming.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                ScreenAccessView view = e.getValue();
                Map<String, AccessLevel> roles = clean(view == null ? null : view.roles());
                Map<String, AccessLevel> users = clean(view == null ? null : view.users());
                if (roles.isEmpty() && users.isEmpty()) {
                    continue; // nothing to override — fall back to UI defaults
                }
                ScreenAccess entry = existing.getOrDefault(key, new ScreenAccess(key));
                entry.setRoles(roles);
                entry.setUsers(users);
                repository.save(entry);
                kept.add(key);
            }
        }

        // Anything no longer present (or emptied out) is removed.
        Set<String> removed = new java.util.TreeSet<>();
        for (String key : existing.keySet()) {
            if (!kept.contains(key)) {
                repository.deleteById(key);
                removed.add(key);
            }
        }
        log.info("screen-access overrides replaced: {} screens now overridden {}, {} reverted to UI defaults {}",
                kept.size(), new java.util.TreeSet<>(kept), removed.size(), removed);
        return overrides();
    }

    /**
     * The effective access level the given user (roles + username) has on each <em>overridden</em>
     * screen, keyed by screen key with the lowercase level ({@code "read"}/{@code "write"}). The
     * level is the strongest of the user's own entry and any of their roles' entries; screens where
     * the user resolves to OFF are omitted. Non-overridden screens use the UI defaults and are
     * resolved client-side (and, on the gateway, from the server-side default catalog).
     */
    @Transactional(readOnly = true)
    public Map<String, String> effectiveLevels(List<String> roles, String username) {
        Map<String, String> out = new TreeMap<>();
        for (ScreenAccess entry : repository.findAll()) {
            AccessLevel level = resolve(entry, roles, username);
            if (level != null) {
                out.put(entry.getScreenKey(), level.wire());
            }
        }
        return out;
    }

    /** The strongest level {@code entry} grants this user via their per-user entry or any role; null = OFF. */
    private static AccessLevel resolve(ScreenAccess entry, List<String> roles, String username) {
        AccessLevel best = username == null ? null : entry.getUsers().get(username);
        if (roles != null) {
            for (String role : roles) {
                best = stronger(best, entry.getRoles().get(role));
                if (best == AccessLevel.WRITE) {
                    break;
                }
            }
        }
        return best;
    }

    private static AccessLevel stronger(AccessLevel a, AccessLevel b) {
        if (a == AccessLevel.WRITE || b == AccessLevel.WRITE) {
            return AccessLevel.WRITE;
        }
        return a != null ? a : b;
    }

    /** Parse a {@code name -> wire-level} map into {@code name -> AccessLevel}, dropping blanks and OFF. */
    private static Map<String, AccessLevel> clean(Map<String, String> values) {
        Map<String, AccessLevel> out = new HashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                String name = e.getKey();
                AccessLevel level = AccessLevel.fromWire(e.getValue());
                if (name != null && !name.isBlank() && level != null) {
                    out.put(name.trim(), level);
                }
            }
        }
        return out;
    }
}
