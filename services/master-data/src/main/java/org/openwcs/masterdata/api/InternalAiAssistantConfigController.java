package org.openwcs.masterdata.api;

import org.openwcs.masterdata.service.AiAssistantConfigService;
import org.openwcs.masterdata.service.AiAssistantConfigService.InternalView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service read of the full AI-assistant config, <b>including the real Anthropic API key</b>,
 * used by the assistant service to make Claude API calls server-side. Deliberately mapped under
 * {@code /internal/**} (not {@code /api/**}): the gateway's public route table and the nginx proxy
 * only forward {@code /api/} and {@code /actuator/}, so this endpoint is reachable only on the
 * internal compose network and is never exposed through the gateway to end users (same treatment as
 * iam's {@code GET /internal/screen-access}).
 *
 * <p>The public {@code GET /api/master-data/ai-assistant} never returns the key; this internal
 * endpoint is the only place it leaves the service.
 */
@RestController
@RequestMapping("/internal/ai-assistant")
public class InternalAiAssistantConfigController {

    private final AiAssistantConfigService service;

    public InternalAiAssistantConfigController(AiAssistantConfigService service) {
        this.service = service;
    }

    @GetMapping
    public InternalView config() {
        return service.internalView();
    }
}
