package org.openwcs.process;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "process-engine",
            "description", "Stores admin-designed process definitions (goods-in, outbound, cycle count) and executes running instances.",
            "status", "skeleton");
    }
}
