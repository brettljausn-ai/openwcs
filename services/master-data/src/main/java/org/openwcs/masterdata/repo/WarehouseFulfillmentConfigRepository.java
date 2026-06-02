package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.WarehouseFulfillmentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseFulfillmentConfigRepository extends JpaRepository<WarehouseFulfillmentConfig, UUID> {
    Optional<WarehouseFulfillmentConfig> findByWarehouseId(UUID warehouseId);
}
