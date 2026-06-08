package org.openwcs.gtp.repo;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StationQueueEntryRepository extends JpaRepository<StationQueueEntry, UUID> {

    List<StationQueueEntry> findByStationIdAndStatusInOrderByArrivalAtAsc(UUID stationId, Collection<String> statuses);

    void deleteByWarehouseId(UUID warehouseId);
}
