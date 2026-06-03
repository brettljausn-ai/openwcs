package org.openwcs.counting;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "counting",
            "description", "Cycle / stock counting: scheduled (ABC-cadence) and ad-hoc count tasks "
                + "over a location / SKU / zone / block, blind vs variance capture, and reconciliation "
                + "against the inventory-expected snapshot (auto-approve within tolerance posts a "
                + "StockAdjusted adjustment; out-of-tolerance spawns a recount). REST under /api/counting/**.",
            "status", "active");
    }
}
