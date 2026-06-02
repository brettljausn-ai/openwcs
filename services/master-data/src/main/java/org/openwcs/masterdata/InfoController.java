package org.openwcs.masterdata;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "master-data",
            "description", "Authoritative catalog: SKUs, UoM/bundles, barcodes & types, locations, equipment, warehouses, per-warehouse SkuProfiles, attribute schemas.",
            "status", "skeleton");
    }
}
