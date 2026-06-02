package org.openwcs.inventory;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InfoController {

    @GetMapping("/")
    public Map<String, String> info() {
        return Map.of(
            "service", "inventory",
            "description", "Real-time stock (SKU x batch/lot x location x HU x status) as a projection of the transaction log; reservations; FEFO/FIFO.",
            "status", "skeleton");
    }
}
