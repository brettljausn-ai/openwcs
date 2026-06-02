package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {
    List<Equipment> findByWarehouseId(UUID warehouseId);

    List<Equipment> findByWarehouseIdAndFamily(UUID warehouseId, String family);
}
