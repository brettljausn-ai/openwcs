package org.openwcs.orders.api;

import java.util.UUID;
import org.openwcs.common.security.Permission;
import org.openwcs.orders.domain.OrderType;
import org.openwcs.orders.service.OrderFlowReportService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Order-flow reporting for the Reporting screen. Read-only; requires ORDER_VIEW like the
 * other order reads (checked against the gateway-forwarded {@code X-Auth-Roles}).
 */
@RestController
@RequestMapping("/api/orders/reports")
public class OrderReportController {

    private static final String ROLES = "X-Auth-Roles";

    private final OrderFlowReportService reports;
    private final AccessGuard guard;

    public OrderReportController(OrderFlowReportService reports, AccessGuard guard) {
        this.reports = reports;
        this.guard = guard;
    }

    /**
     * Expected / active / started counts plus per-day and hour-of-day histograms for one
     * direction. Only INBOUND and OUTBOUND are directions; COUNT/ADJUSTMENT orders have no
     * flow semantics and are rejected.
     */
    @GetMapping("/flow")
    public OrderFlowReport flow(
            @RequestHeader(name = ROLES, required = false) String roles,
            @RequestParam UUID warehouseId,
            @RequestParam OrderType direction,
            @RequestParam(defaultValue = "90") int days) {
        guard.require(roles, Permission.ORDER_VIEW);
        if (direction != OrderType.INBOUND && direction != OrderType.OUTBOUND) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "direction must be INBOUND or OUTBOUND");
        }
        return reports.flow(warehouseId, direction, days);
    }
}
