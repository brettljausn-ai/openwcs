package org.openwcs.processdesigner.assist;

import jakarta.validation.Valid;
import org.openwcs.processdesigner.service.ScriptGovernance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3 design-time endpoints (spec §7.2/§7.3).
 *
 * <ul>
 *   <li>{@code POST /assist/task} — AI task-assist: returns a SUGGESTION the designer can apply; it
 *       never changes server state or runs code. Gated by {@code PROCESS_DESIGN_EDIT} (RbacFilter).
 *       503 when no Anthropic key is configured.</li>
 *   <li>{@code GET /capabilities} — what the frontend should show: scripting flag, AI-assist
 *       availability, and whether the caller may author scripts. Gated by {@code PROCESS_DESIGN_VIEW}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/process-designer")
public class AssistController {

    private final TaskAssistService assist;
    private final AiAssistConfig aiConfig;
    private final ScriptGovernance scriptGovernance;

    public AssistController(TaskAssistService assist, AiAssistConfig aiConfig, ScriptGovernance scriptGovernance) {
        this.assist = assist;
        this.aiConfig = aiConfig;
        this.scriptGovernance = scriptGovernance;
    }

    @PostMapping("/assist/task")
    public TaskAssistResult assistTask(@Valid @RequestBody TaskAssistRequest request) {
        return assist.suggest(request);
    }

    @GetMapping("/capabilities")
    public CapabilitiesView capabilities(
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        return new CapabilitiesView(
                scriptGovernance.scriptingEnabled(),
                aiConfig.configured(),
                scriptGovernance.canAuthorScript(roles));
    }
}
