package org.openwcs.masterdata.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.WarehouseFulfillmentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseFulfillmentConfigRepository extends JpaRepository<WarehouseFulfillmentConfig, UUID> {
    Optional<WarehouseFulfillmentConfig> findByWarehouseId(UUID warehouseId);

    /**
     * Unset every default-shipper reference that points at a shipper whose code starts with
     * {@code prefix} — the FK would otherwise block deleting those shippers (demo-mode disable).
     */
    @Modifying
    @Query("update WarehouseFulfillmentConfig c set c.defaultShipperId = null "
            + "where c.defaultShipperId in (select s.id from Shipper s where s.code like concat(:prefix, '%'))")
    int clearDefaultShipperByCodePrefix(@Param("prefix") String prefix);
}
