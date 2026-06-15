package org.openwcs.masterdata.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.service.AiAssistantConfigService;
import org.openwcs.masterdata.service.AiAssistantConfigService.PublicView;
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
 * AI-assistant configuration. Reads are open to any authenticated user (so the UI can show whether
 * the feature is on and configured), and writes are ADMIN-only and audited (same pattern as demo
 * mode, the hardware emulator, and stock rules).
 *
 * <p>The Anthropic API key is <b>write-only</b>: it can be set here but is never returned. The
 * assistant service reads the real key server-side via the network-only {@code /internal/ai-assistant}
 * endpoint, never through this public API.
 */
@RestController
@RequestMapping("/api/master-data/ai-assistant")
public class AiAssistantConfigController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantConfigController.class);

    private final AiAssistantConfigService service;

    public AiAssistantConfigController(AiAssistantConfigService service) {
        this.service = service;
    }

    /** Current public assistant config (never the key). Open to any authenticated user. */
    @GetMapping
    public PublicView status() {
        return service.publicView();
    }

    /**
     * Update the assistant config (admin-only, audited). An omitted/null {@code apiKey} leaves the
     * stored key unchanged; an explicit empty string clears it; a non-blank value stores it trimmed.
     * The key is never echoed back.
     */
    @PutMapping
    public PublicView update(
            @RequestBody UpdateRequest body,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-User", required = false) String authUser) {
        requireAdmin(roles);
        PublicView view;
        try {
            view = service.update(body.apiKey(), body.model(), body.enabled());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        boolean keyChanged = body.apiKey() != null;
        log.info("AI assistant config updated by {}: enabled={} model={} configured={}{}",
                actor(authUser), view.enabled(), view.model(), view.configured(),
                keyChanged ? " (API key changed)" : "");
        return view;
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The AI assistant is configured by ADMIN only.");
        }
    }

    /** The acting identity for the audit log line; the gateway injects {@code X-Auth-User}. */
    private static String actor(String authUser) {
        return authUser == null || authUser.isBlank() ? "an unidentified admin" : authUser;
    }

    /**
     * Admin update body. All fields optional: a null {@code apiKey} leaves the key unchanged, an
     * empty string clears it; null {@code model}/{@code enabled} leave those unchanged.
     */
    public record UpdateRequest(String apiKey, String model, Boolean enabled) {
    }
}
