package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Sku;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<Sku, UUID> {
    Optional<Sku> findByCode(String code);
}
