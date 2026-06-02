package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.SkuProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuProfileRepository extends JpaRepository<SkuProfile, UUID> {
    Optional<SkuProfile> findBySkuIdAndWarehouseId(UUID skuId, UUID warehouseId);

    List<SkuProfile> findBySkuId(UUID skuId);

    List<SkuProfile> findByWarehouseId(UUID warehouseId);
}
