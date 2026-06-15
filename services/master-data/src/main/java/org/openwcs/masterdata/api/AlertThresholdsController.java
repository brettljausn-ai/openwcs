package org.openwcs.masterdata.api;

import java.util.Map;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.AlertThresholdsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dashboard alert thresholds: the central store for every threshold the dashboards/alerting epic
 * watches. Reads are open (any dashboard/evaluator needs them); the PUT is ADMIN-only (same pattern
 * as the stock rules and demo-mode switches, gated via {@link AccessControl#parseRoles} and audited
 * with {@code X-Auth-User}). The body and response are the full threshold object (defaults merged
 * with stored overrides).
 */
@RestController
@RequestMapping("/api/master-data/alert-thresholds")
public class AlertThresholdsController {

    private static final Logger log = LoggerFactory.getLogger(AlertThresholdsController.class);

    private final AlertThresholdsService thresholds;

    public AlertThresholdsController(AlertThresholdsService thresholds) {
        this.thresholds = thresholds;
    }

    @GetMapping
    public Map<String, Object> get() {
        return thresholds.thresholds();
    }

    /** Replace the stored alert thresholds (admin-only); returns the effective set. */
    @PutMapping
    public Map<String, Object> put(
            @RequestBody Map<String, Object> body,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser) {
        requireAdmin(roles);
        Map<String, Object> effective = thresholds.save(body);
        log.info("dashboard alert thresholds updated by {}: {}", actor(authUser), effective);
        return effective;
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Alert thresholds are administered by ADMIN only.");
        }
    }

    /** The acting identity for the audit log line; the gateway injects {@code X-Auth-User}. */
    private static String actor(String authUser) {
        return authUser == null || authUser.isBlank() ? "an unidentified admin" : authUser;
    }
}
