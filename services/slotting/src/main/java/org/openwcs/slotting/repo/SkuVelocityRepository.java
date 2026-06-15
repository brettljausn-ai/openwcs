package org.openwcs.slotting.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.domain.SkuVelocity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuVelocityRepository extends JpaRepository<SkuVelocity, UUID> {

    Optional<SkuVelocity> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    /** All scores for a warehouse, highest decayed score first (the classifier ranking). */
    List<SkuVelocity> findByWarehouseIdOrderByScoreDesc(UUID warehouseId);

    List<SkuVelocity> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete every velocity row for a warehouse (demo reset). Returns the row count. */
    @Modifying
    @Query("delete from SkuVelocity v where v.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
