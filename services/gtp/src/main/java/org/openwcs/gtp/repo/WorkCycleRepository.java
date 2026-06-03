package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.WorkCycle;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkCycleRepository extends JpaRepository<WorkCycle, UUID> {
    List<WorkCycle> findByStationIdOrderByCreatedAtDesc(UUID stationId);

    List<WorkCycle> findByStationIdAndStatus(UUID stationId, String status);
}
