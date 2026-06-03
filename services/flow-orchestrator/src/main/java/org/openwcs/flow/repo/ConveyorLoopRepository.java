package org.openwcs.flow.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorLoop;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConveyorLoopRepository extends JpaRepository<ConveyorLoop, UUID> {
    List<ConveyorLoop> findByWarehouseId(UUID warehouseId);

    Optional<ConveyorLoop> findByWarehouseIdAndCode(UUID warehouseId, String code);

    void deleteByWarehouseId(UUID warehouseId);
}
