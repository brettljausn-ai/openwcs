package org.openwcs.notification;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "notification",
            "description", "Threshold alert evaluator, alert store, email/webhook delivery, alerts API.",
            "status", "ok");
    }
}
