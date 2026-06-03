package org.openwcs.slotting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReplenishmentTaskRepository extends JpaRepository<ReplenishmentTask, UUID> {
    List<ReplenishmentTask> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    List<ReplenishmentTask> findByWarehouseIdAndToLocationIdAndStatus(UUID warehouseId, UUID toLocationId, String status);
}
