package org.openwcs.flow;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "flow-orchestrator",
            "description", "Material-flow traffic controller: turns process steps into device tasks; routing, sequencing, contention, retries.",
            "status", "skeleton");
    }
}
