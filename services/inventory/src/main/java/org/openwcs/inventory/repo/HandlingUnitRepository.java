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

    /** Count of handling units parked at any of the given locations (occupancy check for block deletion). */
    long countByLocationIdIn(java.util.Collection<UUID> locationIds);

    /** The subset of the given locations that hold at least one handling unit (per-location occupancy). */
    @org.springframework.data.jpa.repository.Query(
            "select distinct h.locationId from HandlingUnit h where h.locationId in :locationIds")
    List<UUID> findDistinctLocationIdByLocationIdIn(
            @org.springframework.data.repository.query.Param("locationIds")
            java.util.Collection<UUID> locationIds);
}
