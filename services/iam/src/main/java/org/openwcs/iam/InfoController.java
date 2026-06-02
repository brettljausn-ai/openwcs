package org.openwcs.iam;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "iam",
            "description", "openWCS authorization model: users -> roles -> coded permissions (Keycloak handles auth). REST per contracts/openapi/iam.yaml.",
            "status", "active");
    }
}
