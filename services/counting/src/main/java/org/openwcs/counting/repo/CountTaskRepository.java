package org.openwcs.counting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.counting.domain.CountTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CountTaskRepository extends JpaRepository<CountTask, UUID> {

    List<CountTask> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    /** Tasks in a given lifecycle status whose routing still needs attention (PENDING / FAILED). */
    List<CountTask> findByStatusAndRoutingStatusIn(String status, java.util.Collection<String> routingStatuses);

    List<CountTask> findByWarehouseId(UUID warehouseId);

    List<CountTask> findByScheduleId(UUID scheduleId);

    /**
     * Bulk-delete every count task of a warehouse in one DELETE statement (demo-mode
     * reset); count lines cascade at the DB level (ON DELETE CASCADE).
     */
    @Modifying
    @Query("delete from CountTask t where t.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
