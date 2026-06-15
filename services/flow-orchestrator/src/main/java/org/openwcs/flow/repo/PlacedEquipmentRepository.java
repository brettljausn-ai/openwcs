package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.PlacedEquipment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlacedEquipmentRepository extends JpaRepository<PlacedEquipment, UUID> {
    List<PlacedEquipment> findByWarehouseId(UUID warehouseId);

    /** Total equipment placed in a warehouse — the denominator for equipment availability. */
    long countByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
