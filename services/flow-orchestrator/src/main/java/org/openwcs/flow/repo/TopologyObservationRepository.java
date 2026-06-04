package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.TopologyObservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopologyObservationRepository extends JpaRepository<TopologyObservation, UUID> {
    /** All observations for a warehouse, grouped by handling unit and ordered in time. */
    List<TopologyObservation> findByWarehouseIdOrderByBarcodeAscObservedAtAsc(UUID warehouseId);

    /** All observations for a warehouse (used by the demo full-reset clear). */
    List<TopologyObservation> findByWarehouseId(UUID warehouseId);
}
