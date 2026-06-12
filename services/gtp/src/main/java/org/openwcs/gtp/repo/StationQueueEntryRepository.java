package org.openwcs.gtp.repo;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StationQueueEntryRepository extends JpaRepository<StationQueueEntry, UUID> {

    List<StationQueueEntry> findByStationIdAndStatusInOrderByArrivalAtAsc(UUID stationId, Collection<String> statuses);

    /** Count entries (excluding one) still active for an HU in the warehouse — drives the store-back gate. */
    long countByWarehouseIdAndHuIdAndStatusInAndIdNot(
            UUID warehouseId, UUID huId, Collection<String> statuses, UUID excludeId);

    void deleteByWarehouseId(UUID warehouseId);

    /** Bulk-delete a warehouse's (legacy) queue entries in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from StationQueueEntry q where q.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
