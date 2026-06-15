package org.openwcs.notification.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.notification.domain.AlertEvent;

/** The alerts API row: an active alert as the dashboards / alert panel consume it. */
public record AlertView(
        UUID id,
        String area,
        String severity,
        String metric,
        BigDecimal value,
        BigDecimal threshold,
        String sinceIso,
        String state) {

    public static AlertView of(AlertEvent a) {
        return new AlertView(
                a.getId(),
                a.getArea(),
                a.getSeverity(),
                a.getMetric(),
                a.getValue(),
                a.getThreshold(),
                a.getOpenedAt() == null ? null : a.getOpenedAt().toString(),
                a.getState());
    }
}
