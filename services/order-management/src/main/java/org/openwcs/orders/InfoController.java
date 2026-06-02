package org.openwcs.orders;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "order-management",
            "description", "Inbound ASNs and outbound orders from the WMS; fulfilment lifecycle; WMS translation.",
            "status", "skeleton");
    }
}
