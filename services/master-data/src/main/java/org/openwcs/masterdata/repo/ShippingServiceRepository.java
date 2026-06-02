package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.ShippingService;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShippingServiceRepository extends JpaRepository<ShippingService, UUID> {
    Optional<ShippingService> findByCode(String code);
}
