package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.TopologyObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopologyObservationRepository extends JpaRepository<TopologyObservation, UUID> {
    /** All observations for a warehouse, grouped by handling unit and ordered in time. */
    List<TopologyObservation> findByWarehouseIdOrderByBarcodeAscObservedAtAsc(UUID warehouseId);

    /** All observations for a warehouse (used by the demo full-reset clear). */
    List<TopologyObservation> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from TopologyObservation e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
