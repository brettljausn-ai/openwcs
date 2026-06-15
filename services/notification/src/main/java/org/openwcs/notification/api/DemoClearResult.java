package org.openwcs.notification.api;

/**
 * Count of demo {@code alert_event} rows removed by a demo clear (build.md §4.8) when demo mode is
 * turned off. Only rows the dashboard seeder backfilled (marked by a {@code |DEMO-} dedupe key) are
 * removed; any real alerts are left untouched.
 */
public record DemoClearResult(int alertsRemoved) {}
