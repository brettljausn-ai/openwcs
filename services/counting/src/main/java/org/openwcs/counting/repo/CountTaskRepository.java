package org.openwcs.counting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.counting.domain.CountTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CountTaskRepository extends JpaRepository<CountTask, UUID> {

    List<CountTask> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    List<CountTask> findByWarehouseId(UUID warehouseId);

    List<CountTask> findByScheduleId(UUID scheduleId);
}
