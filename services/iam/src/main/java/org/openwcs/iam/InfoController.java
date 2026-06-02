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
            "description", "Users, MS Entra SSO + local accounts, RBAC with coded permissions (users -> roles -> permissions).",
            "status", "skeleton");
    }
}
