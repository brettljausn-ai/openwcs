package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.UnitOfMeasure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, UUID> {
    List<UnitOfMeasure> findBySkuId(UUID skuId);

    Optional<UnitOfMeasure> findBySkuIdAndBaseUnitTrue(UUID skuId);
}
