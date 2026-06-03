package org.openwcs.flow.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.HuRoute;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HuRouteRepository extends JpaRepository<HuRoute, UUID> {
    Optional<HuRoute> findFirstByWarehouseIdAndBarcodeAndStatus(UUID warehouseId, String barcode, String status);
}
