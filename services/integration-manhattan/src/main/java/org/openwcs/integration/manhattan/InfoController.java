package org.openwcs.integration.manhattan;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    // "/" for direct hits; "/api/integration/manhattan/" is the gateway-forwarded path
    // (no prefix strip) the UI's adapter status probe calls.
    @GetMapping({"/", "/api/integration/manhattan/"})
    public Map<String, String> info() {
        return Map.of(
            "service", "integration-manhattan",
            "description", "Host integration gateway / anti-corruption layer for Manhattan Active (WM / Omni): REST APIs in and out; inbound ASNs, outbound orders, and stock sync translated to internal events.",
            "status", "skeleton");
    }
}
