package org.openwcs.allocation;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "allocation",
            "description", "Outbound fulfilment prep: pick-location allocation (UoM breakdown) and order cubing (shippers / 1:1). REST per contracts/openapi/allocation.yaml.",
            "status", "active");
    }
}
