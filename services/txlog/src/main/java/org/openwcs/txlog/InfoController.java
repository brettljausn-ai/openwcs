package org.openwcs.txlog;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "txlog",
            "description", "Owns the append-only transaction log in shared Postgres; append, query, replay.",
            "status", "skeleton");
    }
}
