package org.openwcs.counting.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.counting.domain.CountLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CountLineRepository extends JpaRepository<CountLine, UUID> {

    List<CountLine> findByCountTaskId(UUID countTaskId);

    List<CountLine> findByWarehouseId(UUID warehouseId);

    long countByWarehouseId(UUID warehouseId);

    /**
     * Atomically pick the next eligible count line in an area subtree and lock it for this caller.
     * Eligible = PENDING and not yet reserved, with a location inside the given subtree, in the given
     * warehouse. Ordered deterministically (oldest parent count_task first, then line id) so two
     * operators hitting it at once get a stable order. {@code FOR UPDATE SKIP LOCKED} makes each
     * concurrent caller skip a row another transaction has already locked, so they never collide on
     * the same line. Returns the chosen line's id, or empty when nothing is eligible.
     */
    @Query(value = """
            SELECT cl.count_line_id
            FROM counting.count_line cl
            JOIN counting.count_task ct ON ct.count_task_id = cl.count_task_id
            WHERE cl.warehouse_id = :warehouseId
              AND cl.status = 'PENDING'
              AND cl.reserved_by IS NULL
              AND cl.location_id IN (:locationIds)
            ORDER BY ct.created_at ASC, cl.count_line_id ASC
            LIMIT 1
            FOR UPDATE OF cl SKIP LOCKED
            """, nativeQuery = true)
    Optional<UUID> lockNextClaimable(@Param("warehouseId") UUID warehouseId,
                                     @Param("locationIds") Collection<UUID> locationIds);

    /**
     * Free every reservation held by an instance that has not started counting yet. Idempotent:
     * re-running it frees nothing more. Returns how many rows were freed.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE CountLine cl
            SET cl.reservedBy = NULL, cl.reservedAt = NULL, cl.reservedByInstance = NULL
            WHERE cl.reservedByInstance = :instanceId AND cl.startedAt IS NULL
            """)
    int releaseByInstance(@Param("instanceId") String instanceId);

    /**
     * Free stale reservations: reserved, not started, and reserved before the cutoff (now minus TTL).
     * Used by the background sweeper to reclaim lines an operator never finished. Returns how many
     * rows were freed.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE CountLine cl
            SET cl.reservedBy = NULL, cl.reservedAt = NULL, cl.reservedByInstance = NULL
            WHERE cl.reservedBy IS NOT NULL AND cl.startedAt IS NULL AND cl.reservedAt < :cutoff
            """)
    int releaseStale(@Param("cutoff") Instant cutoff);
}
