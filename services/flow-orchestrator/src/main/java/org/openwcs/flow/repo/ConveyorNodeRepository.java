package org.openwcs.flow.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConveyorNodeRepository extends JpaRepository<ConveyorNode, UUID> {
    List<ConveyorNode> findByWarehouseId(UUID warehouseId);

    Optional<ConveyorNode> findByWarehouseIdAndCode(UUID warehouseId, String code);
}
