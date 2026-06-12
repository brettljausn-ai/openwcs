package org.openwcs.flow.repo;

import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.DeviceTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTaskRepository extends JpaRepository<DeviceTask, UUID> {
    List<DeviceTask> findByCorrelationIdOrderByCreatedAtAsc(UUID correlationId);

    /** All device tasks for a warehouse (used by the demo full-reset clear). */
    List<DeviceTask> findByWarehouseId(UUID warehouseId);

    /**
     * Recent device tasks (newest first) for the transport overview, with optional filters.
     * A null filter parameter is ignored, so the same query serves "all", "by warehouse",
     * "by status", "by family" and "by equipment" in any combination.
     */
    @Query("select t from DeviceTask t"
            + " where (:warehouseId is null or t.warehouseId = :warehouseId)"
            + " and (:status is null or t.status = :status)"
            + " and (:family is null or t.family = :family)"
            + " and (:equipmentId is null or t.equipmentId = :equipmentId)"
            + " order by t.createdAt desc")
    List<DeviceTask> search(@Param("warehouseId") UUID warehouseId,
                            @Param("status") String status,
                            @Param("family") String family,
                            @Param("equipmentId") UUID equipmentId,
                            Pageable pageable);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from DeviceTask e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);

    /**
     * Reporting: completed STORE/RETRIEVE (and AutoStore BIN_*) movements per storage location per
     * day, for the last {@code days} days. The location is the {@code locationId} the dispatcher
     * stamps into the task payload ({@code InductionQueueService}); tasks dispatched without one
     * (e.g. degraded slotting) aggregate under a null location. Day = the task's creation date.
     */
    @Query(value = """
            SELECT CAST(t.payload ->> 'locationId' AS uuid) AS "locationId",
                   date(t.created_at) AS "day",
                   count(*) FILTER (WHERE t.command IN ('STORE', 'BIN_STORE')) AS "stores",
                   count(*) FILTER (WHERE t.command IN ('RETRIEVE', 'BIN_RETRIEVE')) AS "retrieves"
            FROM flow.device_task t
            WHERE t.warehouse_id = :warehouseId
              AND t.status = 'COMPLETED'
              AND t.command IN ('STORE', 'BIN_STORE', 'RETRIEVE', 'BIN_RETRIEVE')
              AND t.created_at >= CURRENT_DATE - (:days - 1)
            GROUP BY 1, 2
            ORDER BY 2, 1
            """, nativeQuery = true)
    List<StorageMovementAgg> storageMovements(@Param("warehouseId") UUID warehouseId,
                                              @Param("days") int days);

    /**
     * Reporting: device-task throughput (COMPLETED) and failures (FAILED) per equipment per day,
     * for the last {@code days} days. Equipment label = the best available name: a placed
     * equipment's code (when the task carries an equipment id that is placed in this warehouse),
     * else the raw equipment id, else the family. Day = the task's creation date.
     */
    @Query(value = """
            SELECT t.family AS "family",
                   COALESCE((SELECT min(p.code) FROM flow.placed_equipment p
                             WHERE p.equipment_id = t.equipment_id AND p.warehouse_id = :warehouseId),
                            CAST(t.equipment_id AS text), t.family) AS "equipment",
                   date(t.created_at) AS "day",
                   count(*) FILTER (WHERE t.status = 'COMPLETED') AS "completed",
                   count(*) FILTER (WHERE t.status = 'FAILED') AS "failed"
            FROM flow.device_task t
            WHERE t.warehouse_id = :warehouseId
              AND t.created_at >= CURRENT_DATE - (:days - 1)
            GROUP BY t.family, t.equipment_id, date(t.created_at)
            ORDER BY 3, 1, 2
            """, nativeQuery = true)
    List<DeviceMovementAgg> deviceMovements(@Param("warehouseId") UUID warehouseId,
                                            @Param("days") int days);

    interface StorageMovementAgg {
        UUID getLocationId();

        Date getDay();

        long getStores();

        long getRetrieves();
    }

    interface DeviceMovementAgg {
        String getFamily();

        String getEquipment();

        Date getDay();

        long getCompleted();

        long getFailed();
    }
}
