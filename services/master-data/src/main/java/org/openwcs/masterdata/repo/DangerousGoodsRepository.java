package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.DangerousGoods;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DangerousGoodsRepository extends JpaRepository<DangerousGoods, UUID> {
    Optional<DangerousGoods> findBySkuId(UUID skuId);
}
