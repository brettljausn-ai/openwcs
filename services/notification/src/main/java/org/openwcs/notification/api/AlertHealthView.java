package org.openwcs.notification.api;

import java.util.List;
import java.util.UUID;

/**
 * Alert-system-health snapshot (ISA-18.2 "measure the alarm system"). Summarises the alert ledger so
 * an operator can see whether the alarm system itself is healthy: how many alerts are active now, the
 * per-day open/clear volume, which keys are chattering (flapping), and which alerts have gone stale
 * (left OPEN too long).
 */
public record AlertHealthView(
        ActiveBySeverity activeBySeverity,
        List<PerDay> perDay,
        List<Chattering> chattering,
        List<Stale> stale) {

    /** Current count of active (OPEN or ACKED) alerts split by severity. */
    public record ActiveBySeverity(long warning, long critical) {}

    /** Alerts opened and cleared on a given UTC day. */
    public record PerDay(String day, long opened, long cleared) {}

    /**
     * A flapping (area, metric): it cleared repeatedly inside the window. {@code flaps} is the number of
     * CLEARED rows in the window for that key (proxy for open→clear cycles).
     */
    public record Chattering(String area, String metric, long flaps) {}

    /** An alert that has stayed OPEN longer than the staleness threshold. */
    public record Stale(UUID id, String area, String metric, long ageMin) {}
}
