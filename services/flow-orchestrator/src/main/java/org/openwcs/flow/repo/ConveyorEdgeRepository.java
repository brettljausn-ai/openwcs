package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorEdge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConveyorEdgeRepository extends JpaRepository<ConveyorEdge, UUID> {
    List<ConveyorEdge> findByWarehouseId(UUID warehouseId);

    void deleteByWarehouseId(UUID warehouseId);
}
