package org.openwcs.inventory.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.HandlingUnit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HandlingUnitRepository extends JpaRepository<HandlingUnit, UUID> {

    List<HandlingUnit> findByWarehouseId(UUID warehouseId);

    Optional<HandlingUnit> findByWarehouseIdAndCode(UUID warehouseId, String code);

    /** Active handling units of a given type, across all warehouses (guards HU-type archiving). */
    long countByHuTypeIdAndStatus(UUID huTypeId, String status);
}
