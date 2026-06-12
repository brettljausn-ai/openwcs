package org.openwcs.txlog.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.txlog.service.TransactionLogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demo mode for txlog (build.md §4.8). When demo mode is switched off the system gets a full
 * operational reset; the transaction journal is part of that reset, so the stock-transaction
 * history does not outlive the stock it describes. The journal is global (events are not
 * warehouse-scoped rows), so the clear wipes the whole log — demo systems are single-warehouse
 * by construction. Admin-only, mirroring the demo controllers of the other services.
 */
@RestController
@RequestMapping("/api/txlog/demo")
public class DemoController {

    /** What a journal clear removed. */
    public record DemoClearResult(long events, long outboxMessages) {}

    private final TransactionLogService service;

    public DemoController(TransactionLogService service) {
        this.service = service;
    }

    /** Wipe the transaction journal and its outbox (admin-only). */
    @PostMapping("/clear")
    public DemoClearResult clear(@RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        TransactionLogService.ClearCounts counts = service.clearAll();
        return new DemoClearResult(counts.events(), counts.outboxMessages());
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Demo mode is administered by ADMIN only.");
        }
    }
}
