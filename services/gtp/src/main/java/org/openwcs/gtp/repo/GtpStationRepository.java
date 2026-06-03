package org.openwcs.gtp.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GtpStationRepository extends JpaRepository<GtpStation, UUID> {
    List<GtpStation> findByWarehouseId(UUID warehouseId);

    Optional<GtpStation> findByWarehouseIdAndCode(UUID warehouseId, String code);
}
