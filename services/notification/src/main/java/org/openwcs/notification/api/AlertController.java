package org.openwcs.notification.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.service.AlertService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alerts API (Dashboards &amp; alerting epic): list active alerts + supervisor acknowledgement. */
@RestController
@RequestMapping("/api/notification/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /** Active (OPEN / ACKED) alerts for a warehouse, newest first. */
    @GetMapping
    public List<AlertView> list(@RequestParam UUID warehouseId) {
        return alerts.activeForWarehouse(warehouseId).stream().map(AlertView::of).toList();
    }

    /**
     * Acknowledge an alert (SUPERVISOR / ADMIN, enforced by {@link RbacFilter}). The actor is the
     * gateway-forwarded {@code X-Auth-User}; falls back to "system" when identity is not propagated.
     */
    @PostMapping("/{id}/ack")
    public AlertView ack(@PathVariable UUID id,
                         @RequestHeader(name = "X-Auth-User", required = false) String actor) {
        AlertEvent acked = alerts.acknowledge(id, actor == null || actor.isBlank() ? "system" : actor);
        return AlertView.of(acked);
    }
}
