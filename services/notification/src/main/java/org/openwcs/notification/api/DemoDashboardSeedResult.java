package org.openwcs.notification.api;

/**
 * Result of the notification demo dashboard seed: how many alert_event rows were backfilled to
 * light up the andon board (/api/notification/alerts) and alert-system-health (/alerts/health).
 */
public record DemoDashboardSeedResult(int alertsCreated) {
}
