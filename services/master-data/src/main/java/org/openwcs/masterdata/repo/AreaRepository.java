package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Area;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AreaRepository extends JpaRepository<Area, UUID> {

    List<Area> findByWarehouseId(UUID warehouseId);

    List<Area> findByWarehouseIdAndParentAreaId(UUID warehouseId, UUID parentAreaId);

    /** Top-level areas in a warehouse (no parent). */
    List<Area> findByWarehouseIdAndParentAreaIdIsNull(UUID warehouseId);

    Optional<Area> findByWarehouseIdAndCode(UUID warehouseId, String code);

    /** Resolve a code case-insensitively within a warehouse (resolve endpoint). */
    Optional<Area> findByWarehouseIdAndCodeIgnoreCase(UUID warehouseId, String code);

    /**
     * The area's subtree: the area itself plus every descendant area, walking parent_area_id with a
     * recursive CTE. Tables are schema-qualified because this is a native query (Hibernate does not
     * rewrite native table names with the default schema).
     */
    @Query(value = """
        WITH RECURSIVE subtree AS (
            SELECT area_id FROM master_data.area WHERE area_id = :areaId
            UNION ALL
            SELECT a.area_id FROM master_data.area a
            JOIN subtree s ON a.parent_area_id = s.area_id
        )
        SELECT area_id FROM subtree
        """, nativeQuery = true)
    List<UUID> findSubtreeAreaIds(@Param("areaId") UUID areaId);

    /**
     * Location ids in the area's subtree: every location whose area_id is the given area OR any
     * descendant area (recursive CTE over area by parent_area_id, then locations in that set).
     */
    @Query(value = """
        WITH RECURSIVE subtree AS (
            SELECT area_id FROM master_data.area WHERE area_id = :areaId
            UNION ALL
            SELECT a.area_id FROM master_data.area a
            JOIN subtree s ON a.parent_area_id = s.area_id
        )
        SELECT l.location_id FROM master_data.location l
        WHERE l.area_id IN (SELECT area_id FROM subtree)
        """, nativeQuery = true)
    List<UUID> findLocationIdsInSubtree(@Param("areaId") UUID areaId);

    /** Location ids assigned directly to a single area (non-recursive). */
    @Query(value = """
        SELECT l.location_id FROM master_data.location l WHERE l.area_id = :areaId
        """, nativeQuery = true)
    List<UUID> findLocationIdsDirect(@Param("areaId") UUID areaId);
}
