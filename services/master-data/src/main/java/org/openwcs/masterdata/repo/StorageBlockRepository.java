package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.StorageBlock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageBlockRepository extends JpaRepository<StorageBlock, UUID> {
    List<StorageBlock> findByWarehouseId(UUID warehouseId);

    List<StorageBlock> findByWarehouseIdAndStorageType(UUID warehouseId, String storageType);

    Optional<StorageBlock> findByWarehouseIdAndCode(UUID warehouseId, String code);
}
