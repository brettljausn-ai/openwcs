package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.PutInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutInstructionRepository extends JpaRepository<PutInstruction, UUID> {
    List<PutInstruction> findByWorkCycleId(UUID workCycleId);

    /** All put instructions for a set of work cycles (used by the demo full-reset clear). */
    List<PutInstruction> findByWorkCycleIdIn(List<UUID> workCycleIds);
}
