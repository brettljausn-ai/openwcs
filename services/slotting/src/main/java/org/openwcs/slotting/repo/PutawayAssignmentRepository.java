package org.openwcs.slotting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutawayAssignmentRepository extends JpaRepository<PutawayAssignment, UUID> {
    List<PutawayAssignment> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<PutawayAssignment> findByWarehouseIdAndBlockId(UUID warehouseId, UUID blockId);
}
