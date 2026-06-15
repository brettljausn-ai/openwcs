package org.openwcs.inventory.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.HandlingUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HandlingUnitRepository extends JpaRepository<HandlingUnit, UUID> {

    List<HandlingUnit> findByWarehouseId(UUID warehouseId);

    // ------------------------------------------------------------------ Dashboard aggregates

    /** Total handling units in a warehouse (dashboard huCount). */
    long countByWarehouseId(UUID warehouseId);

    /** Handling units created in a warehouse since an instant (dashboard husReceivedToday). */
    long countByWarehouseIdAndCreatedAtGreaterThanEqual(UUID warehouseId, Instant since);

    /**
     * Handling units of a warehouse parked at any of the given (receiving / UNKNOWN-ish)
     * locations, oldest first — the putaway backlog: received but not yet stored away.
     */
    @Query("select h from HandlingUnit h where h.warehouseId = :warehouseId"
            + " and h.locationId in :locationIds order by h.createdAt asc")
    List<HandlingUnit> findBacklogByWarehouseIdAndLocationIdIn(
            @Param("warehouseId") UUID warehouseId,
            @Param("locationIds") java.util.Collection<UUID> locationIds);

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

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from HandlingUnit e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
