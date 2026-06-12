package org.openwcs.flow.repo;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.ScanStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScanStatRepository extends JpaRepository<ScanStat, ScanStat.Key> {

    /**
     * Atomic upsert of today's scan-quality counter for a scan point: a single INSERT … ON
     * CONFLICT … DO UPDATE per scan (never read-modify-write, so concurrent scans on the same
     * node/day can't lose increments). Every call counts one scan; {@code noRead} / {@code unknown}
     * are 0 or 1 flags for the same scan.
     */
    @Modifying
    @Query(value = """
            INSERT INTO flow.scan_stat AS s (warehouse_id, node_code, day, scans, no_reads, unknowns)
            VALUES (:warehouseId, :nodeCode, CURRENT_DATE, 1, :noRead, :unknown)
            ON CONFLICT (warehouse_id, node_code, day) DO UPDATE
            SET scans = s.scans + 1,
                no_reads = s.no_reads + EXCLUDED.no_reads,
                unknowns = s.unknowns + EXCLUDED.unknowns
            """, nativeQuery = true)
    int bump(@Param("warehouseId") UUID warehouseId, @Param("nodeCode") String nodeCode,
             @Param("noRead") int noRead, @Param("unknown") int unknown);

    /** Per-node daily scan-quality rows for the last {@code days} days (today inclusive). */
    @Query(value = """
            SELECT node_code AS "node", day AS "day", scans AS "scans",
                   no_reads AS "noReads", unknowns AS "unknowns"
            FROM flow.scan_stat
            WHERE warehouse_id = :warehouseId AND day >= CURRENT_DATE - (:days - 1)
            ORDER BY day, node_code
            """, nativeQuery = true)
    List<ScanQualityAgg> quality(@Param("warehouseId") UUID warehouseId, @Param("days") int days);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from ScanStat e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);

    interface ScanQualityAgg {
        String getNode();

        Date getDay();

        long getScans();

        long getNoReads();

        long getUnknowns();
    }
}
