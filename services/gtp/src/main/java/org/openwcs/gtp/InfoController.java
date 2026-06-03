package org.openwcs.gtp;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "gtp",
            "description", "Goods-to-person station execution: configure GTP stations + nodes, "
                + "present a stock HU to generate a put-to-light put-list across order destinations "
                + "(ORDER_LOCATION or PUT_WALL mode), confirm puts, complete destinations. "
                + "REST under /api/gtp/**.",
            "status", "active");
    }
}
