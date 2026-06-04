package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.TaskLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskLineRepository extends JpaRepository<TaskLine, UUID> {
    List<TaskLine> findByWorkCycleId(UUID workCycleId);

    /** All task lines for a set of work cycles (used by the demo full-reset clear). */
    List<TaskLine> findByWorkCycleIdIn(List<UUID> workCycleIds);
}
