package org.openwcs.flow.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.HuRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HuRouteRepository extends JpaRepository<HuRoute, UUID> {
    Optional<HuRoute> findFirstByWarehouseIdAndBarcodeAndStatus(UUID warehouseId, String barcode, String status);

    /** All routes for a warehouse (used by the demo full-reset clear). */
    List<HuRoute> findByWarehouseId(UUID warehouseId);

    /** The most recent route for a barcode regardless of status (for reads / status display). */
    Optional<HuRoute> findFirstByWarehouseIdAndBarcodeOrderByCreatedAtDesc(UUID warehouseId, String barcode);

    /** Loop occupancy: active HUs whose last-scanned node is in the given loop. */
    int countByWarehouseIdAndCurrentLoopAndStatus(UUID warehouseId, String currentLoop, String status);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from HuRoute e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
