package org.openwcs.gtp.repo;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationQueueEntryRepository extends JpaRepository<StationQueueEntry, UUID> {

    List<StationQueueEntry> findByStationIdAndStatusInOrderByArrivalAtAsc(UUID stationId, Collection<String> statuses);

    /** Count entries (excluding one) still active for an HU in the warehouse — drives the store-back gate. */
    long countByWarehouseIdAndHuIdAndStatusInAndIdNot(
            UUID warehouseId, UUID huId, Collection<String> statuses, UUID excludeId);

    void deleteByWarehouseId(UUID warehouseId);
}
