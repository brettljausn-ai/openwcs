package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.EquipmentFunctionPoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentFunctionPointRepository extends JpaRepository<EquipmentFunctionPoint, UUID> {
    List<EquipmentFunctionPoint> findByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
