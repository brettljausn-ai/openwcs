package org.openwcs.iam.api;

import java.util.Map;
import org.openwcs.common.security.AccessControl;
import org.openwcs.iam.service.WarehouseAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-user warehouse access (build.md §4.8). Reads are open to the signed-in user for their own
 * mapping ({@code /me}); listing or editing other users' mappings is ADMIN-only — enforced here on
 * the forwarded {@code X-Auth-Roles} so the rule holds regardless of the caller (full server-side
 * enforcement, not just UI gating).
 */
@RestController
@RequestMapping("/api/iam/warehouse-access")
public class WarehouseAccessController {

    private final WarehouseAccessService service;

    public WarehouseAccessController(WarehouseAccessService service) {
        this.service = service;
    }

    /** The current user's own warehouse access (the UI auto-selects the default on login). */
    @GetMapping("/me")
    public WarehouseAccessView mine(@RequestHeader(value = "X-Auth-User", required = false) String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user.");
        }
        return service.forUser(username);
    }

    /** Every user's warehouse access — admin screen. ADMIN only. */
    @GetMapping
    public Map<String, WarehouseAccessView> all(@RequestHeader(value = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return service.all();
    }

    /** One user's warehouse access. ADMIN only. */
    @GetMapping("/{username}")
    public WarehouseAccessView forUser(
            @PathVariable String username,
            @RequestHeader(value = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return service.forUser(username);
    }

    /** Replace a user's allowed warehouses + default. ADMIN only. */
    @PutMapping("/{username}")
    public WarehouseAccessView setForUser(
            @PathVariable String username,
            @RequestBody Requests.SetWarehouseAccess request,
            @RequestHeader(value = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return service.replaceForUser(username, request);
    }

    private static void requireAdmin(String rolesHeader) {
        if (!AccessControl.parseRoles(rolesHeader).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Warehouse access is administered by ADMIN only.");
        }
    }
}
