package org.openwcs.inventory.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BatchRepository extends JpaRepository<Batch, UUID> {
    Optional<Batch> findByWarehouseIdAndSkuIdAndBatchNumber(UUID warehouseId, UUID skuId, String batchNumber);

    List<Batch> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<Batch> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from Batch e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
