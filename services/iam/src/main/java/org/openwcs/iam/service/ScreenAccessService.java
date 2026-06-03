package org.openwcs.iam.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.openwcs.iam.api.ScreenAccessView;
import org.openwcs.iam.domain.ScreenAccess;
import org.openwcs.iam.repo.ScreenAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configurable per-screen access (build.md §4.8). The UI owns the canonical screen catalog
 * (ui/src/auth/screens.ts) and each screen's default roles; this service stores only the
 * *overrides* that replace those defaults. Screen keys are opaque UI-owned strings — whatever
 * is sent is persisted. Only overridden screens have entries.
 */
@Service
public class ScreenAccessService {

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

        Set<String> kept = new HashSet<>();
        if (incoming != null) {
            for (Map.Entry<String, ScreenAccessView> e : incoming.entrySet()) {
                String key = e.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                ScreenAccessView view = e.getValue();
                Set<String> roles = clean(view == null ? null : view.roles());
                Set<String> users = clean(view == null ? null : view.users());
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
        for (String key : existing.keySet()) {
            if (!kept.contains(key)) {
                repository.deleteById(key);
            }
        }
        return overrides();
    }

    /** The screen keys the given user (roles + username) can access, based on stored overrides. */
    @Transactional(readOnly = true)
    public Set<String> accessibleKeys(List<String> roles, String username) {
        boolean admin = roles != null && roles.contains("ADMIN");
        Set<String> keys = new java.util.TreeSet<>();
        for (ScreenAccess entry : repository.findAll()) {
            boolean overridden = !entry.getRoles().isEmpty() || !entry.getUsers().isEmpty();
            if (!overridden) {
                continue;
            }
            if (admin
                    || (username != null && entry.getUsers().contains(username))
                    || (roles != null && entry.getRoles().stream().anyMatch(roles::contains))) {
                keys.add(entry.getScreenKey());
            }
        }
        return keys;
    }

    private static Set<String> clean(List<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
        }
        return out;
    }
}
