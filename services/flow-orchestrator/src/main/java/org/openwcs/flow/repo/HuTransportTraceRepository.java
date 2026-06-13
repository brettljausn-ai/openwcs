package org.openwcs.flow.repo;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.HuTransportTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HuTransportTraceRepository extends JpaRepository<HuTransportTrace, UUID> {

    /** An HU's transport timeline, ts ASC (timeline order). */
    List<HuTransportTrace> findByHuIdOrderByTsAsc(UUID huId);

    /** An HU's transport timeline scoped to a warehouse, ts ASC. */
    List<HuTransportTrace> findByWarehouseIdAndHuIdOrderByTsAsc(UUID warehouseId, UUID huId);

    /** An HU's rows of one event type from {@code ts} onward, ts ASC — the current transit leg's
     *  SCANNED waypoints for the live twin (bounded by the running CONVEY task's start time). */
    List<HuTransportTrace> findByWarehouseIdAndHuIdAndEventAndTsGreaterThanEqualOrderByTsAsc(
            UUID warehouseId, UUID huId, String event, java.time.Instant ts);

    /** All trace rows for a warehouse (used by the demo full-reset clear). */
    List<HuTransportTrace> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from HuTransportTrace e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);

    /**
     * Reporting: one transit-time sample per induction entry, the milliseconds between the entry's
     * INDUCTED trace row (HU left storage onto the conveyor) and its ARRIVED row (HU reached the
     * induction point), attributed to the arrival's day. Last {@code days} days; entries still in
     * transit (no ARRIVED yet) are excluded. p50/p95 are computed in Java over the day's samples.
     */
    @Query(value = """
            SELECT date(a.arrived) AS "day",
                   CAST(EXTRACT(EPOCH FROM (a.arrived - i.inducted)) * 1000 AS bigint) AS "ms"
            FROM (SELECT induction_entry_id, min(ts) AS inducted
                  FROM flow.hu_transport_trace
                  WHERE warehouse_id = :warehouseId AND event = 'INDUCTED'
                    AND induction_entry_id IS NOT NULL
                  GROUP BY induction_entry_id) i
            JOIN (SELECT induction_entry_id, min(ts) AS arrived
                  FROM flow.hu_transport_trace
                  WHERE warehouse_id = :warehouseId AND event = 'ARRIVED'
                    AND induction_entry_id IS NOT NULL
                  GROUP BY induction_entry_id) a USING (induction_entry_id)
            WHERE a.arrived >= CURRENT_DATE - (:days - 1)
            ORDER BY 1
            """, nativeQuery = true)
    List<TransitSampleAgg> transitSamples(@Param("warehouseId") UUID warehouseId,
                                          @Param("days") int days);

    interface TransitSampleAgg {
        Date getDay();

        long getMs();
    }
}
