package org.openwcs.notification.delivery;

import org.openwcs.notification.domain.AlertEvent;

/** A best-effort sink for alert lifecycle transitions (OPEN / CLEAR). */
public interface AlertDelivery {

    /** Fired when an alert opens (a metric first breaches its threshold). */
    void onOpen(AlertEvent alert);

    /** Fired when an active alert clears (the metric drops back under threshold). */
    void onClear(AlertEvent alert);
}
