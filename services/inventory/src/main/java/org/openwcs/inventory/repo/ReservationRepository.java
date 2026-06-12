package org.openwcs.inventory.repo;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByOrderRef(String orderRef);

    List<Reservation> findByWarehouseId(UUID warehouseId);

    List<Reservation> findByWarehouseIdAndSkuIdAndStatus(UUID warehouseId, UUID skuId, String status);

    List<Reservation> findByWarehouseIdAndStatus(UUID warehouseId, String status);

    /** Quantity currently HELD against a SKU in a warehouse (subtract from on-hand for ATP). */
    @Query("""
        select coalesce(sum(r.qty), 0) from Reservation r
        where r.warehouseId = :warehouseId
          and r.skuId = :skuId
          and r.status = 'HELD'
        """)
    BigDecimal sumHeld(@Param("warehouseId") UUID warehouseId, @Param("skuId") UUID skuId);

    /** Quantity HELD against a SKU pinned to a specific location (location-scoped ATP). */
    @Query("""
        select coalesce(sum(r.qty), 0) from Reservation r
        where r.warehouseId = :warehouseId
          and r.skuId = :skuId
          and r.locationId = :locationId
          and r.status = 'HELD'
        """)
    BigDecimal sumHeldAtLocation(
            @Param("warehouseId") UUID warehouseId,
            @Param("skuId") UUID skuId,
            @Param("locationId") UUID locationId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from Reservation e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
