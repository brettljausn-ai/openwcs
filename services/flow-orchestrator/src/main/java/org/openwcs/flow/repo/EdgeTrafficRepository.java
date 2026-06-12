package org.openwcs.flow.repo;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.EdgeTraffic;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EdgeTrafficRepository extends JpaRepository<EdgeTraffic, EdgeTraffic.Key> {

    /**
     * Atomic upsert of today's traffic counter for a directed edge: a single INSERT … ON CONFLICT
     * … DO UPDATE per ROUTE answer (never read-modify-write, so concurrent scans over the same
     * edge/day can't lose increments).
     */
    @Modifying
    @Query(value = """
            INSERT INTO flow.edge_traffic AS e (warehouse_id, from_node, to_node, day, count)
            VALUES (:warehouseId, :fromNode, :toNode, CURRENT_DATE, 1)
            ON CONFLICT (warehouse_id, from_node, to_node, day) DO UPDATE
            SET count = e.count + 1
            """, nativeQuery = true)
    int bump(@Param("warehouseId") UUID warehouseId, @Param("fromNode") String fromNode,
             @Param("toNode") String toNode);

    /** Per-edge daily traffic rows for the last {@code days} days (today inclusive). */
    @Query(value = """
            SELECT from_node AS "fromNode", to_node AS "toNode", day AS "day", count AS "count"
            FROM flow.edge_traffic
            WHERE warehouse_id = :warehouseId AND day >= CURRENT_DATE - (:days - 1)
            ORDER BY day, from_node, to_node
            """, nativeQuery = true)
    List<TrafficAgg> traffic(@Param("warehouseId") UUID warehouseId, @Param("days") int days);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from EdgeTraffic e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);

    interface TrafficAgg {
        String getFromNode();

        String getToNode();

        Date getDay();

        long getCount();
    }
}
