package org.openwcs.integration.host;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "integration-host",
            "description", "Canonical, vendor-neutral openWCS Host API: a single versioned contract any WMS/ERP "
                    + "integrates against (orders, ASNs in; confirmations out). Vendor adapters (SAP/Manhattan) "
                    + "translate their protocols into this API.",
            "status", "mvp");
    }
}
