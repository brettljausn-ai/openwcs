package org.openwcs.slotting.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.domain.SkuVelocity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuVelocityRepository extends JpaRepository<SkuVelocity, UUID> {

    Optional<SkuVelocity> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    /** All scores for a warehouse, highest decayed score first (the classifier ranking). */
    List<SkuVelocity> findByWarehouseIdOrderByScoreDesc(UUID warehouseId);

    List<SkuVelocity> findByWarehouseId(UUID warehouseId);
}
