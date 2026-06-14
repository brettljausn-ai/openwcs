package org.openwcs.iam.api;

import java.util.Map;
import org.openwcs.iam.service.ScreenAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service read of the full screen-access override map, used by the gateway to enforce
 * read-vs-write screen access (build.md §4.8). Deliberately mapped under {@code /internal/**} (not
 * {@code /api/**}): the gateway's public route table and the nginx proxy only forward {@code /api/}
 * and {@code /actuator/}, so this endpoint is reachable only on the internal compose network.
 *
 * <p>It returns the same shape as {@code GET /api/iam/screen-access} (keyed by screen key with
 * {@code { roles: {role: level}, users: {user: level} }}); the gateway combines it with its
 * server-side default catalog to resolve a user's effective level per screen.
 */
@RestController
@RequestMapping("/internal/screen-access")
public class InternalScreenAccessController {

    private final ScreenAccessService service;

    public InternalScreenAccessController(ScreenAccessService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, ScreenAccessView> overrides() {
        return service.overrides();
    }
}
