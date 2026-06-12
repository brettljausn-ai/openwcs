package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.PutInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PutInstructionRepository extends JpaRepository<PutInstruction, UUID> {
    List<PutInstruction> findByWorkCycleId(UUID workCycleId);

    /** All put instructions for a set of work cycles (used by the demo full-reset clear). */
    List<PutInstruction> findByWorkCycleIdIn(List<UUID> workCycleIds);

    /** Bulk-delete every put instruction of the given stations' work cycles (demo-mode reset). */
    @Modifying
    @Query("delete from PutInstruction p where p.workCycleId in "
            + "(select c.id from WorkCycle c where c.stationId in :stationIds)")
    int deleteBulkByStationIds(@Param("stationIds") java.util.Collection<UUID> stationIds);
}
