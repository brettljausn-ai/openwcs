package org.openwcs.iam.api;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.openwcs.iam.service.IamService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import java.util.Map;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Users, roles, and the coded-permission catalog (build.md §4.8). */
@RestController
@RequestMapping("/api/iam")
public class IamController {

    private final IamService service;

    public IamController(IamService service) {
        this.service = service;
    }

    /** The calling user's saved UI language (frontend only). Falls back to {@code en}. The caller is
     *  the gateway-provided {@code X-Auth-User}. */
    @GetMapping("/me/language")
    public Map<String, String> myLanguage(
            @RequestHeader(value = "X-Auth-User", required = false) String username) {
        String lang = username == null || username.isBlank() ? "en" : service.languageOf(username);
        return Map.of("language", lang);
    }

    /** Persist the calling user's UI language. Unknown codes are coerced to English. */
    @PutMapping("/me/language")
    public Map<String, String> setMyLanguage(
            @RequestHeader(value = "X-Auth-User", required = false) String username,
            @RequestBody Map<String, String> body) {
        if (username == null || username.isBlank()) {
            return Map.of("language", "en");
        }
        return Map.of("language", service.setLanguage(username, body.get("language")));
    }

    /** The code-defined permission catalog. */
    @GetMapping("/permissions")
    public List<String> permissions() {
        return service.permissionCatalog();
    }

    // ------------------------------------------------------------------- Roles
    @GetMapping("/roles")
    public List<RoleView> listRoles() {
        return service.listRoles();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody Requests.CreateRole request) {
        RoleView role = service.createRole(request);
        return ResponseEntity.created(URI.create("/api/iam/roles/" + role.name())).body(role);
    }

    @GetMapping("/roles/{name}")
    public RoleView getRole(@PathVariable String name) {
        return service.getRole(name);
    }

    @PutMapping("/roles/{name}/permissions")
    public RoleView setRolePermissions(@PathVariable String name, @RequestBody Requests.SetPermissions request) {
        return service.setRolePermissions(name, request);
    }

    // ------------------------------------------------------------------- Users
    @GetMapping("/users")
    public List<UserView> listUsers() {
        return service.listUsers();
    }

    @PostMapping("/users")
    public ResponseEntity<UserView> createUser(@Valid @RequestBody Requests.CreateUser request) {
        UserView user = service.createUser(request);
        return ResponseEntity.created(URI.create("/api/iam/users/" + user.username())).body(user);
    }

    @GetMapping("/users/{username}")
    public UserView getUser(@PathVariable String username) {
        return service.getUser(username);
    }

    @PutMapping("/users/{username}/roles")
    public UserView setUserRoles(@PathVariable String username, @RequestBody Requests.SetRoles request) {
        return service.setUserRoles(username, request);
    }

    /** A user's effective permissions (union across assigned roles). */
    @GetMapping("/users/{username}/permissions")
    public Set<String> userPermissions(@PathVariable String username) {
        return service.effectivePermissions(username);
    }
}
