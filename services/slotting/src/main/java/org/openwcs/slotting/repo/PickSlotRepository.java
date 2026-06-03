package org.openwcs.slotting.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.PickSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PickSlotRepository extends JpaRepository<PickSlot, UUID> {
    List<PickSlot> findByWarehouseId(UUID warehouseId);

    List<PickSlot> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    List<PickSlot> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<PickSlot> findByLocationId(UUID locationId);

    /** Distinct warehouses that have pick faces — drives the opportunistic top-off scheduler. */
    @Query("select distinct p.warehouseId from PickSlot p where p.status = 'ACTIVE'")
    List<UUID> findDistinctActiveWarehouseIds();
}
