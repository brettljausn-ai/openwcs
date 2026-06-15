package org.openwcs.notification.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.notification.domain.AlertEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertEventRepository extends JpaRepository<AlertEvent, UUID> {

    /** The single active (OPEN or ACKED) alert for a dedupe key, if any (the active-dedupe index keeps it unique). */
    Optional<AlertEvent> findFirstByDedupeKeyAndStateIn(String dedupeKey, List<String> states);

    /** Active alerts (OPEN or ACKED) for a warehouse, newest first. */
    List<AlertEvent> findByWarehouseIdAndStateInOrderByOpenedAtDesc(UUID warehouseId, List<String> states);

    // -- Alert-system-health aggregations (ISA-18.2 "measure the alarm system"). --------------------
    // All optionally scope to one warehouse via the :warehouseId param, where NULL means "all
    // warehouses" (the `:wh IS NULL OR warehouse_id = :wh` idiom).

    /**
     * Current count of ACTIVE (OPEN or ACKED) alerts grouped by severity. Returns rows of
     * {@code [severity, count]}; severities with no active alerts are absent (zero-filled by caller).
     */
    @Query(value = """
            SELECT severity AS severity, COUNT(*) AS cnt
            FROM notification.alert_event
            WHERE state IN ('OPEN', 'ACKED')
              AND (:warehouseId IS NULL OR warehouse_id = :warehouseId)
            GROUP BY severity
            """, nativeQuery = true)
    List<ActiveBySeverityRow> activeBySeverity(@Param("warehouseId") UUID warehouseId);

    /**
     * Per-UTC-day count of alerts OPENED within the window. Rows {@code [day, opened]}; only days with
     * activity appear (the caller zero-fills the full range). {@code day} is the date the alert opened.
     */
    @Query(value = """
            SELECT (opened_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS cnt
            FROM notification.alert_event
            WHERE opened_at >= :since
              AND (:warehouseId IS NULL OR warehouse_id = :warehouseId)
            GROUP BY 1
            """, nativeQuery = true)
    List<PerDayRow> openedPerDay(@Param("warehouseId") UUID warehouseId, @Param("since") Instant since);

    /**
     * Per-UTC-day count of alerts CLEARED within the window. Rows {@code [day, cleared]}; the day is the
     * date the alert cleared (cleared_at).
     */
    @Query(value = """
            SELECT (cleared_at AT TIME ZONE 'UTC')::date AS day, COUNT(*) AS cnt
            FROM notification.alert_event
            WHERE cleared_at IS NOT NULL
              AND cleared_at >= :since
              AND (:warehouseId IS NULL OR warehouse_id = :warehouseId)
            GROUP BY 1
            """, nativeQuery = true)
    List<PerDayRow> clearedPerDay(@Param("warehouseId") UUID warehouseId, @Param("since") Instant since);

    /**
     * Chattering (flapping) keys: (area, metric) that CLEARED two or more times inside the window. A
     * "flap" is one CLEARED row in the window for that key (proxy for an open→clear cycle), so flaps is
     * the count of CLEARED rows per (area, metric). Most flaps first, ties broken by area/metric.
     */
    @Query(value = """
            SELECT area AS area, metric AS metric, COUNT(*) AS flaps
            FROM notification.alert_event
            WHERE state = 'CLEARED'
              AND cleared_at IS NOT NULL
              AND cleared_at >= :since
              AND (:warehouseId IS NULL OR warehouse_id = :warehouseId)
            GROUP BY area, metric
            HAVING COUNT(*) >= 2
            ORDER BY flaps DESC, area ASC, metric ASC
            """, nativeQuery = true)
    List<ChatteringRow> chattering(@Param("warehouseId") UUID warehouseId, @Param("since") Instant since);

    /**
     * Stale alerts: still OPEN (not yet ACKED or CLEARED) and opened before {@code threshold}. Oldest
     * first. The caller derives age in minutes from opened_at.
     */
    @Query(value = """
            SELECT alert_event_id AS id, area AS area, metric AS metric, opened_at AS openedAt
            FROM notification.alert_event
            WHERE state = 'OPEN'
              AND opened_at < :threshold
              AND (:warehouseId IS NULL OR warehouse_id = :warehouseId)
            ORDER BY opened_at ASC
            """, nativeQuery = true)
    List<StaleRow> stale(@Param("warehouseId") UUID warehouseId, @Param("threshold") Instant threshold);

    interface ActiveBySeverityRow {
        String getSeverity();

        long getCnt();
    }

    interface PerDayRow {
        java.time.LocalDate getDay();

        long getCnt();
    }

    interface ChatteringRow {
        String getArea();

        String getMetric();

        long getFlaps();
    }

    interface StaleRow {
        UUID getId();

        String getArea();

        String getMetric();

        Instant getOpenedAt();
    }
}
