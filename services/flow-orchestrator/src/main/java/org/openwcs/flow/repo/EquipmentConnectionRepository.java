package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.EquipmentConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentConnectionRepository extends JpaRepository<EquipmentConnection, UUID> {
    List<EquipmentConnection> findByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
