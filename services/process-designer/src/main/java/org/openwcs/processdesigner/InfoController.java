package org.openwcs.processdesigner;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "process-designer",
            "description", "Engine + storage for configurable handheld operator processes: versioned "
                + "JSON process definitions (one ACTIVE per process key), client-driven offline "
                + "runtime with server task-step checkpoints, and a curated task library that calls "
                + "existing audited services with the operator's forwarded identity. REST under "
                + "/api/process-designer/**.",
            "status", "active");
    }
}
