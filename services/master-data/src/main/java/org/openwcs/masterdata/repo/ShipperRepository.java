package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Shipper;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShipperRepository extends JpaRepository<Shipper, UUID> {
    List<Shipper> findByWarehouseId(UUID warehouseId);

    Optional<Shipper> findByWarehouseIdAndCode(UUID warehouseId, String code);
}
