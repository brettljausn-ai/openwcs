package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.WorkCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkCycleRepository extends JpaRepository<WorkCycle, UUID> {
    List<WorkCycle> findByStationIdOrderByCreatedAtDesc(UUID stationId);

    List<WorkCycle> findByStationIdAndStatus(UUID stationId, String status);

    /** All work cycles for a set of stations (used by the demo full-reset clear). */
    List<WorkCycle> findByStationIdIn(List<UUID> stationIds);

    /** Bulk-delete every work cycle of the given stations (demo-mode reset). */
    @Modifying
    @Query("delete from WorkCycle c where c.stationId in :stationIds")
    int deleteBulkByStationIds(@Param("stationIds") java.util.Collection<UUID> stationIds);
}
