package org.openwcs.inventory.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.SerialUnit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SerialUnitRepository extends JpaRepository<SerialUnit, UUID> {
    Optional<SerialUnit> findByWarehouseIdAndSkuIdAndSerialNumber(UUID warehouseId, UUID skuId, String serialNumber);
}
