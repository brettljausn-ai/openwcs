package org.openwcs.masterdata.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Shipper;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipperRepository extends JpaRepository<Shipper, UUID> {
    List<Shipper> findByWarehouseId(UUID warehouseId);

    Optional<Shipper> findByWarehouseIdAndCode(UUID warehouseId, String code);

    /** Bulk-delete shippers whose code starts with {@code prefix} (single DELETE statement). */
    @Modifying
    @Query("delete from Shipper s where s.code like concat(:prefix, '%')")
    int deleteByCodePrefix(@Param("prefix") String prefix);
}
