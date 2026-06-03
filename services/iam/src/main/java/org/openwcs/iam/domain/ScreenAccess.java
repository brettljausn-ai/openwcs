package org.openwcs.iam.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;

/**
 * A per-screen access override (build.md §4.8). The UI owns the canonical screen catalog
 * (ui/src/auth/screens.ts) and each screen's default roles; this entity only records the
 * overrides that replace those defaults. {@code screenKey} is an opaque, UI-owned string.
 */
@Entity
@Table(name = "screen_access")
public class ScreenAccess extends Auditable {

    @Id
    @Column(name = "screen_key", nullable = false, updatable = false)
    private String screenKey;

    /** Roles allowed to access the screen (replaces the UI default when non-empty). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "screen_access_role", joinColumns = @JoinColumn(name = "screen_key"))
    @Column(name = "role", nullable = false)
    private Set<String> roles = new HashSet<>();

    /** Individual usernames explicitly allowed onto the screen. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "screen_access_user", joinColumns = @JoinColumn(name = "screen_key"))
    @Column(name = "username", nullable = false)
    private Set<String> users = new HashSet<>();

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

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }

    public Set<String> getUsers() {
        return users;
    }

    public void setUsers(Set<String> users) {
        this.users = users;
    }
}
