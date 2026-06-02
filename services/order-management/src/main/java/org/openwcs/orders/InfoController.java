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
            "description", "Outbound orders + fulfilment lifecycle; allocates stock via the inventory reservation API. REST per contracts/openapi/order-management.yaml.",
            "status", "active");
    }
}
