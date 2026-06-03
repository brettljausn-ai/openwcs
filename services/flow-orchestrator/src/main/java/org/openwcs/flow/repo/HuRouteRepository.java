package org.openwcs.flow.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.HuRoute;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HuRouteRepository extends JpaRepository<HuRoute, UUID> {
    Optional<HuRoute> findFirstByWarehouseIdAndBarcodeAndStatus(UUID warehouseId, String barcode, String status);

    /** The most recent route for a barcode regardless of status (for reads / status display). */
    Optional<HuRoute> findFirstByWarehouseIdAndBarcodeOrderByCreatedAtDesc(UUID warehouseId, String barcode);

    /** Loop occupancy: active HUs whose last-scanned node is in the given loop. */
    int countByWarehouseIdAndCurrentLoopAndStatus(UUID warehouseId, String currentLoop, String status);
}
