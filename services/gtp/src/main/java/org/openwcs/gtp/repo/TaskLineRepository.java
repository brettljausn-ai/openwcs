package org.openwcs.gtp.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.TaskLine;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskLineRepository extends JpaRepository<TaskLine, UUID> {
    List<TaskLine> findByWorkCycleId(UUID workCycleId);
}
