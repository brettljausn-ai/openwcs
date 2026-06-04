package org.openwcs.gtp.api;

import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.gtp.service.GtpDemoService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for goods-to-person (build.md §4.8). Admin-only full operational reset for a
 * warehouse: purge all transactional GTP state (work cycles, put instructions, task lines,
 * workplace sessions and destination demand), keeping the station/node configuration. Invoked
 * when demo mode is turned off.
 */
@RestController
@RequestMapping("/api/gtp/demo")
public class DemoController {

    private final GtpDemoService demo;

    public DemoController(GtpDemoService demo) {
        this.demo = demo;
    }

    /** Full operational reset for a warehouse (admin-only). */
    @PostMapping("/clear")
    public DemoClearResult clear(
            @RequestParam UUID warehouseId,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        return demo.clear(warehouseId);
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
