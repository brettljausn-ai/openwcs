package org.openwcs.iam.api;

import java.util.Map;
import org.openwcs.common.security.AccessControl;
import org.openwcs.iam.service.ScreenAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configurable per-screen access (build.md §4.8). The UI owns the canonical screen catalog
 * (ui/src/auth/screens.ts); this endpoint stores per-screen *overrides* that replace each
 * screen's default roles. The response is a plain object keyed by screen key with
 * {@code { roles?: string[], users?: string[] }}, matching the UI's AuthContext.
 */
@RestController
@RequestMapping("/api/iam/screen-access")
public class ScreenAccessController {

    private static final Logger log = LoggerFactory.getLogger(ScreenAccessController.class);

    private final ScreenAccessService service;

    public ScreenAccessController(ScreenAccessService service) {
        this.service = service;
    }

    /** The full override map (only overridden screens have entries). */
    @GetMapping
    public Map<String, ScreenAccessView> overrides() {
        return service.overrides();
    }

    /** Replace the whole override map. Screens absent from the body fall back to UI defaults. */
    @PutMapping
    public Map<String, ScreenAccessView> replace(
            @RequestBody Map<String, ScreenAccessView> overrides,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        log.info("screen-access override replacement requested by {}: {} screens in the submitted map",
                actor == null ? "unauthenticated caller" : actor,
                overrides == null ? 0 : overrides.size());
        return service.replaceAll(overrides);
    }

    /**
     * The current user's effective access level per <em>overridden</em> screen (forwarded
     * {@code X-Auth-User}/{@code X-Auth-Roles}), keyed by screen key with {@code "read"}/{@code "write"}.
     * Screens where the user resolves to OFF are omitted; non-overridden screens use the UI defaults
     * and are resolved client-side.
     */
    @GetMapping("/me")
    public Map<String, String> mine(
            @RequestHeader(value = "X-Auth-User", required = false) String username,
            @RequestHeader(value = "X-Auth-Roles", required = false) String rolesHeader) {
        return service.effectiveLevels(AccessControl.parseRoles(rolesHeader), username);
    }
}
