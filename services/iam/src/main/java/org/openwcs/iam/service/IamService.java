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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the openWCS authorization model (build.md §4.8): the coded-permission catalog,
 * roles → permissions, users → roles, and a user's effective permissions.
 */
@Service
public class IamService {

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
        return RoleView.from(roles.save(role));
    }

    @Transactional
    public RoleView setRolePermissions(String name, Requests.SetPermissions request) {
        Role role = requireRole(name);
        role.setPermissions(validatePermissions(request.permissions()));
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
        return UserView.from(users.save(user));
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
