package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.DeviceTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
