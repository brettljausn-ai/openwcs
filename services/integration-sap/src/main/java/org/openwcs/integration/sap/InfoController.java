package org.openwcs.integration.sap;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "integration-sap",
            "description", "Host integration gateway / anti-corruption layer for SAP S/4HANA & SAP HANA: OData / BAPI / RFC / IDoc in and out; master-data and order sync translated to internal events.",
            "status", "skeleton");
    }
}
