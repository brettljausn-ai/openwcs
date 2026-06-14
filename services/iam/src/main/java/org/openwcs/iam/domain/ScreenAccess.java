package org.openwcs.iam.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;

/**
 * A per-screen access override (build.md §4.8). The UI owns the canonical screen catalog
 * (ui/src/auth/screens.ts) and each screen's default access; this entity only records the
 * overrides that replace those defaults. {@code screenKey} is an opaque, UI-owned string.
 *
 * <p>Each role / user is mapped to an {@link AccessLevel} (READ or WRITE). A role/user that is
 * <em>absent</em> from the map has no access (OFF) when the screen is overridden.
 */
@Entity
@Table(name = "screen_access")
public class ScreenAccess extends Auditable {

    @Id
    @Column(name = "screen_key", nullable = false, updatable = false)
    private String screenKey;

    /** Roles granted access to the screen, each at READ or WRITE (replaces the UI default). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "screen_access_role", joinColumns = @JoinColumn(name = "screen_key"))
    @MapKeyColumn(name = "role")
    @Column(name = "access_level", nullable = false)
    @Enumerated(EnumType.STRING)
    private Map<String, AccessLevel> roles = new HashMap<>();

    /** Individual usernames granted access, each at READ or WRITE. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "screen_access_user", joinColumns = @JoinColumn(name = "screen_key"))
    @MapKeyColumn(name = "username")
    @Column(name = "access_level", nullable = false)
    @Enumerated(EnumType.STRING)
    private Map<String, AccessLevel> users = new HashMap<>();

    public ScreenAccess() {
    }

    public ScreenAccess(String screenKey) {
        this.screenKey = screenKey;
    }

    public String getScreenKey() {
        return screenKey;
    }

    public void setScreenKey(String screenKey) {
        this.screenKey = screenKey;
    }

    public Map<String, AccessLevel> getRoles() {
        return roles;
    }

    public void setRoles(Map<String, AccessLevel> roles) {
        this.roles = roles;
    }

    public Map<String, AccessLevel> getUsers() {
        return users;
    }

    public void setUsers(Map<String, AccessLevel> users) {
        this.users = users;
    }
}
