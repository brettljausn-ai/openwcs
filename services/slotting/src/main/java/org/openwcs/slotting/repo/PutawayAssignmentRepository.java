package org.openwcs.slotting.repo;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.PutawayAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PutawayAssignmentRepository extends JpaRepository<PutawayAssignment, UUID> {
    List<PutawayAssignment> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<PutawayAssignment> findByWarehouseIdAndBlockId(UUID warehouseId, UUID blockId);

    /** Active (not yet completed/cancelled) assignments in a block — the engine's occupancy ledger. */
    List<PutawayAssignment> findByWarehouseIdAndBlockIdAndStatusIn(
            UUID warehouseId, UUID blockId, Collection<String> statuses);

    /** A handling unit's open assignments — superseded on re-assign, closed on store confirmation. */
    List<PutawayAssignment> findByWarehouseIdAndHuIdAndStatusIn(
            UUID warehouseId, UUID huId, Collection<String> statuses);
}
