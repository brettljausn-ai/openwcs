package org.openwcs.slotting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, UUID> {
    List<ReplenishmentTask> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    List<ReplenishmentTask> findByWarehouseIdAndToLocationIdAndStatus(UUID warehouseId, UUID toLocationId, String status);

    /**
     * Bulk-delete open (PLANNED) replenishment tasks for a warehouse (demo reset). On a demo box the
     * only PLANNED tasks are the ones the dashboard seeder created; clearing them takes the
     * replenishment dashboard back to zero when demo mode is turned off. Returns the row count.
     */
    @Modifying
    @Query("delete from ReplenishmentTask t where t.warehouseId = :warehouseId and t.status = 'PLANNED'")
    int deletePlannedByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
