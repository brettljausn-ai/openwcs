package org.openwcs.inventory.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.Batch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchRepository extends JpaRepository<Batch, UUID> {
    Optional<Batch> findByWarehouseIdAndSkuIdAndBatchNumber(UUID warehouseId, UUID skuId, String batchNumber);

    List<Batch> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<Batch> findByWarehouseId(UUID warehouseId);
}
