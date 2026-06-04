package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.WarehouseLevel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseLevelRepository extends JpaRepository<WarehouseLevel, UUID> {
    List<WarehouseLevel> findByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
