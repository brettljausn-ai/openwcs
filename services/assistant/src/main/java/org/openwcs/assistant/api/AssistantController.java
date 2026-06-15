package org.openwcs.assistant.api;

import jakarta.validation.Valid;
import java.util.List;
import org.openwcs.assistant.chat.ChatMessage;
import org.openwcs.assistant.service.AssistantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Assistant API. {@code POST /chat} runs a warehouse-scoped AI chat turn (RBAC: any authenticated
 * user holding a view permission — gated by {@link RbacFilter}); {@code GET /status} reports whether
 * the assistant is enabled/configured and the active model (no key).
 */
@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        List<ChatMessage> messages = request.messages().stream()
                .map(t -> new ChatMessage(t.role(), t.content()))
                .toList();
        return new ChatResponse(service.chat(request.warehouseId(), messages));
    }

    @GetMapping("/status")
    public AssistantService.StatusView status() {
        return service.status();
    }
}
