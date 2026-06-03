package org.openwcs.flow.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorController;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConveyorControllerRepository extends JpaRepository<ConveyorController, UUID> {
    List<ConveyorController> findByWarehouseId(UUID warehouseId);

    Optional<ConveyorController> findByWarehouseIdAndCode(UUID warehouseId, String code);

    void deleteByWarehouseId(UUID warehouseId);
}
