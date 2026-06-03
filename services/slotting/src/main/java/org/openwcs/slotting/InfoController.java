package org.openwcs.slotting;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "slotting",
            "description", "Storage-location assignment (put-away) for automated rack / GTP blocks, "
                + "fixed pick-face slotting + min/max replenishment, and off-peak re-slotting. "
                + "REST under /api/slotting/**.",
            "status", "active");
    }
}
