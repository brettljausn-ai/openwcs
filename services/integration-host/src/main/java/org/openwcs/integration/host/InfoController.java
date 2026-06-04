package org.openwcs.integration.host;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    // "/" is reachable when hitting the service directly; "/api/host/" is the path the
    // gateway forwards (it does not strip the prefix), so the UI's adapter status probe
    // and any direct caller both resolve to this identity endpoint.
    @GetMapping({"/", "/api/host/"})
    public Map<String, String> info() {
        return Map.of(
            "service", "integration-host",
            "description", "Canonical, vendor-neutral openWCS Host API: a single versioned contract any WMS/ERP "
                    + "integrates against (orders, ASNs in; confirmations out). Vendor adapters (SAP/Manhattan) "
                    + "translate their protocols into this API.",
            "status", "mvp");
    }
}
