package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.PlacedEquipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacedEquipmentRepository extends JpaRepository<PlacedEquipment, UUID> {
    List<PlacedEquipment> findByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
