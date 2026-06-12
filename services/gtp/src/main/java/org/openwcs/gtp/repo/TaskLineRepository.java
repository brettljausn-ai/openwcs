package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.TaskLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskLineRepository extends JpaRepository<TaskLine, UUID> {
    List<TaskLine> findByWorkCycleId(UUID workCycleId);

    /** All task lines for a set of work cycles (used by the demo full-reset clear). */
    List<TaskLine> findByWorkCycleIdIn(List<UUID> workCycleIds);

    /** Bulk-delete every task line of the given stations' work cycles (demo-mode reset). */
    @Modifying
    @Query("delete from TaskLine l where l.workCycleId in "
            + "(select c.id from WorkCycle c where c.stationId in :stationIds)")
    int deleteBulkByStationIds(@Param("stationIds") java.util.Collection<UUID> stationIds);
}
