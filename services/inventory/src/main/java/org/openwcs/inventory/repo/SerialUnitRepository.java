package org.openwcs.inventory.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.SerialUnit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SerialUnitRepository extends JpaRepository<SerialUnit, UUID> {
    Optional<SerialUnit> findByWarehouseIdAndSkuIdAndSerialNumber(UUID warehouseId, UUID skuId, String serialNumber);

    List<SerialUnit> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from SerialUnit e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
