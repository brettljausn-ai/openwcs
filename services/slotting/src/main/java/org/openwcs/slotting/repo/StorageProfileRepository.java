package org.openwcs.slotting.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.domain.StorageProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageProfileRepository extends JpaRepository<StorageProfile, UUID> {
    List<StorageProfile> findByWarehouseId(UUID warehouseId);

    List<StorageProfile> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    Optional<StorageProfile> findByWarehouseIdAndSkuIdAndBlockId(UUID warehouseId, UUID skuId, UUID blockId);
}
