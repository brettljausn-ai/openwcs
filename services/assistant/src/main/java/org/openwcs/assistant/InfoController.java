package org.openwcs.assistant;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "assistant",
            "description", "AI chat over warehouse data (Anthropic Claude tool-use loop).",
            "status", "ok");
    }
}
