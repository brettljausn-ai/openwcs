package org.openwcs.slotting.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /**
     * The next eligible task to claim in an area: PLANNED, unreserved, whose destination pick face is
     * inside the area subtree, scoped by warehouse. Ordered deterministically by priority (EMERGENCY,
     * then SCHEDULED, then OPPORTUNISTIC) then oldest-first. {@code FOR UPDATE SKIP LOCKED} so two
     * operators claiming concurrently lock different rows and never get the same task. The row is
     * returned locked; the caller (in the same transaction) writes the reservation onto it.
     */
    @Query(value = """
            SELECT * FROM slotting.replenishment_task
             WHERE warehouse_id = :warehouseId
               AND status = 'PLANNED'
               AND reserved_by IS NULL
               AND to_location_id IN (:locationIds)
             ORDER BY CASE priority
                          WHEN 'EMERGENCY' THEN 0
                          WHEN 'SCHEDULED' THEN 1
                          ELSE 2
                      END,
                      created_at
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<ReplenishmentTask> lockNextClaimable(@Param("warehouseId") UUID warehouseId,
                                                  @Param("locationIds") Collection<UUID> locationIds);

    /**
     * Release every reservation held by a runtime instance that the operator has not yet committed to
     * (PLANNED and started_at IS NULL). NULLs the four reservation fields. Returns rows freed;
     * idempotent (a second call frees nothing).
     */
    @Modifying
    @Query("""
            update ReplenishmentTask t
               set t.reservedBy = null, t.reservedAt = null,
                   t.reservedByInstance = null
             where t.reservedByInstance = :instanceId
               and t.status = 'PLANNED'
               and t.startedAt is null
            """)
    int releaseByInstance(@Param("instanceId") String instanceId);

    /**
     * Stale-reservation sweep: free reservations the operator never committed to (PLANNED, started_at
     * IS NULL) that were taken before the cutoff. Returns rows freed.
     */
    @Modifying
    @Query("""
            update ReplenishmentTask t
               set t.reservedBy = null, t.reservedAt = null,
                   t.reservedByInstance = null
             where t.status = 'PLANNED'
               and t.startedAt is null
               and t.reservedBy is not null
               and t.reservedAt < :cutoff
            """)
    int releaseStale(@Param("cutoff") Instant cutoff);
}
