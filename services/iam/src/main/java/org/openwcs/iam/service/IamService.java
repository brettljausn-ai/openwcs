package org.openwcs.iam.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.openwcs.common.security.Permission;
import org.openwcs.iam.api.NotFoundException;
import org.openwcs.iam.api.Requests;
import org.openwcs.iam.api.RoleView;
import org.openwcs.iam.api.UserView;
import org.openwcs.iam.domain.AppUser;
import org.openwcs.iam.domain.Role;
import org.openwcs.iam.repo.AppUserRepository;
import org.openwcs.iam.repo.RoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the openWCS authorization model (build.md §4.8): the coded-permission catalog,
 * roles → permissions, users → roles, and a user's effective permissions.
 */
@Service
public class IamService {

    private static final Logger log = LoggerFactory.getLogger(IamService.class);

    private final AppUserRepository users;
    private final RoleRepository roles;

    public IamService(AppUserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    /** The code-defined permission catalog. */
    public List<String> permissionCatalog() {
        return java.util.Arrays.stream(Permission.values()).map(Enum::name).toList();
    }

    // ------------------------------------------------------------------- Language (frontend only)
    /** The languages the frontend ships; anything else falls back to English. */
    private static final Set<String> LANGUAGES = Set.of("en", "de", "fr", "es", "zh");
    private static final String DEFAULT_LANGUAGE = "en";

    /** A user's saved UI language, or {@code en} when the user has no row / no preference. */
    @Transactional(readOnly = true)
    public String languageOf(String username) {
        return users.findByUsername(username).map(AppUser::getLanguage).orElse(DEFAULT_LANGUAGE);
    }

    /**
     * Persist a user's UI language. Unknown codes are coerced to English. The row is created if the
     * user is not registered yet (e.g. a Keycloak login that has not been managed in IAM), so the
     * preference still sticks across devices. Returns the language actually stored.
     */
    @Transactional
    public String setLanguage(String username, String language) {
        String lang = language != null && LANGUAGES.contains(language) ? language : DEFAULT_LANGUAGE;
        AppUser user = users.findByUsername(username).orElseGet(() -> {
            AppUser created = new AppUser();
            created.setUsername(username);
            return created;
        });
        user.setLanguage(lang);
        users.save(user);
        log.info("user {} set UI language to {}", username, lang);
        return lang;
    }

    // ------------------------------------------------------------------- Roles
    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAll().stream().map(RoleView::from).toList();
    }

    @Transactional(readOnly = true)
    public RoleView getRole(String name) {
        return RoleView.from(requireRole(name));
    }

    @Transactional
    public RoleView createRole(Requests.CreateRole request) {
        Set<String> permissions = validatePermissions(request.permissions());
        Role role = new Role();
        role.setName(request.name());
        role.setDescription(request.description());
        role.setPermissions(permissions);
        RoleView created = RoleView.from(roles.save(role));
        log.info("role {} created with {} permissions: {}", role.getName(), permissions.size(), new TreeSet<>(permissions));
        return created;
    }

    @Transactional
    public RoleView setRolePermissions(String name, Requests.SetPermissions request) {
        Role role = requireRole(name);
        Set<String> before = new TreeSet<>(role.getPermissions());
        role.setPermissions(validatePermissions(request.permissions()));
        log.info("role {} permissions replaced: {} -> {} (affects every user holding the role)",
                name, before, new TreeSet<>(role.getPermissions()));
        return RoleView.from(role);
    }

    // ------------------------------------------------------------------- Users
    @Transactional(readOnly = true)
    public List<UserView> listUsers() {
        return users.findAll().stream().map(UserView::from).toList();
    }

    @Transactional(readOnly = true)
    public UserView getUser(String username) {
        return UserView.from(requireUser(username));
    }

    @Transactional
    public UserView createUser(Requests.CreateUser request) {
        AppUser user = new AppUser();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setExternalId(request.externalId());
        UserView created = UserView.from(users.save(user));
        log.info("user {} ('{}') created in the IAM catalog (no roles yet)",
                user.getUsername(), user.getDisplayName());
        return created;
    }

    @Transactional
    public UserView setUserRoles(String username, Requests.SetRoles request) {
        AppUser user = requireUser(username);
        Set<Role> assigned = new HashSet<>();
        if (request.roleNames() != null) {
            for (String roleName : request.roleNames()) {
                assigned.add(requireRole(roleName));
            }
        }
        user.setRoles(assigned);
        log.info("user {} roles replaced: now {} (effective permissions are the union across these roles)",
                username, assigned.stream().map(Role::getName).collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
        return UserView.from(user);
    }

    /** A user's effective permissions = the union across all assigned roles. */
    @Transactional(readOnly = true)
    public Set<String> effectivePermissions(String username) {
        Set<String> permissions = new TreeSet<>();
        for (Role role : requireUser(username).getRoles()) {
            permissions.addAll(role.getPermissions());
        }
        return permissions;
    }

    private Role requireRole(String name) {
        return roles.findByName(name).orElseThrow(() -> new NotFoundException("Role", name));
    }

    private AppUser requireUser(String username) {
        return users.findByUsername(username).orElseThrow(() -> new NotFoundException("User", username));
    }

    /** Reject any permission code not in the catalog (build.md §4.8: catalog is code-owned). */
    private static Set<String> validatePermissions(Set<String> permissions) {
        Set<String> validated = new HashSet<>();
        if (permissions != null) {
            for (String permission : permissions) {
                validated.add(Permission.valueOf(permission).name());
            }
        }
        return validated;
    }
}
