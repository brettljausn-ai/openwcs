package org.openwcs.slotting.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.domain.BlockPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlockPolicyRepository extends JpaRepository<BlockPolicy, UUID> {
    Optional<BlockPolicy> findByBlockId(UUID blockId);

    /** Blocks with re-slotting switched on — drives the off-peak re-slot scheduler. */
    List<BlockPolicy> findByReslotEnabledTrue();

    /** Policies for a warehouse — the auto-ABC classifier reads its EWMA/share knobs from here. */
    List<BlockPolicy> findByWarehouseId(UUID warehouseId);
}
