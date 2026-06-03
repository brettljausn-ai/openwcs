package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Location;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LocationRepository extends JpaRepository<Location, UUID> {
    Optional<Location> findByWarehouseIdAndCode(UUID warehouseId, String code);

    List<Location> findByParentId(UUID parentId);

    /** Storage locations in a block (the slotting candidate pool). */
    List<Location> findByWarehouseIdAndBlockId(UUID warehouseId, UUID blockId);

    /** Paged search within a warehouse with optional purpose / type / parent / block filters. */
    @Query("""
        select l from Location l
        where l.warehouseId = :warehouseId
          and (:purpose is null or l.purpose = :purpose)
          and (:locationType is null or l.locationType = :locationType)
          and (:parentId is null or l.parentId = :parentId)
          and (:blockId is null or l.blockId = :blockId)
        """)
    Page<Location> search(
            @Param("warehouseId") UUID warehouseId,
            @Param("purpose") String purpose,
            @Param("locationType") String locationType,
            @Param("parentId") UUID parentId,
            @Param("blockId") UUID blockId,
            Pageable pageable);
}
