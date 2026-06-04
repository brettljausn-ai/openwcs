package org.openwcs.inventory.repo;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, UUID> {

    List<Stock> findByWarehouseIdAndSkuId(UUID warehouseId, UUID skuId);

    List<Stock> findByWarehouseId(UUID warehouseId);

    List<Stock> findByLocationId(UUID locationId);

    /** Count of stock rows sitting at any of the given locations (occupancy check for block deletion). */
    long countByLocationIdIn(java.util.Collection<UUID> locationIds);

    /**
     * Resolve the exact physical bucket (null-safe on the nullable batch/hu keys),
     * mirroring the {@code stock_bucket_uniq} constraint. Used by the projection to
     * upsert a stock row when applying a movement event.
     */
    @Query("""
        select s from Stock s
        where s.warehouseId = :warehouseId
          and s.skuId = :skuId
          and s.locationId = :locationId
          and s.status = :status
          and ((:batchId is null and s.batchId is null) or s.batchId = :batchId)
          and ((:huId is null and s.huId is null) or s.huId = :huId)
        """)
    Optional<Stock> findBucket(
            @Param("warehouseId") UUID warehouseId,
            @Param("skuId") UUID skuId,
            @Param("batchId") UUID batchId,
            @Param("locationId") UUID locationId,
            @Param("huId") UUID huId,
            @Param("status") String status);

    /** Total on-hand AVAILABLE quantity for a SKU in a warehouse (before reservations). */
    @Query("""
        select coalesce(sum(s.qty), 0) from Stock s
        where s.warehouseId = :warehouseId
          and s.skuId = :skuId
          and s.status = 'AVAILABLE'
        """)
    BigDecimal sumAvailable(@Param("warehouseId") UUID warehouseId, @Param("skuId") UUID skuId);

    /**
     * Lock the AVAILABLE stock rows for a SKU so concurrent reservations are serialized
     * and cannot over-allocate (the available-to-promise check must read a stable total).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from Stock s
        where s.warehouseId = :warehouseId
          and s.skuId = :skuId
          and s.status = 'AVAILABLE'
        """)
    List<Stock> lockAvailable(@Param("warehouseId") UUID warehouseId, @Param("skuId") UUID skuId);

    /** On-hand AVAILABLE quantity for a SKU at a specific location. */
    @Query("""
        select coalesce(sum(s.qty), 0) from Stock s
        where s.warehouseId = :warehouseId
          and s.skuId = :skuId
          and s.locationId = :locationId
          and s.status = 'AVAILABLE'
        """)
    BigDecimal sumAvailableAtLocation(
            @Param("warehouseId") UUID warehouseId,
            @Param("skuId") UUID skuId,
            @Param("locationId") UUID locationId);

    /** Lock the AVAILABLE rows for a SKU at one location (location-scoped allocation). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s from Stock s
        where s.warehouseId = :warehouseId
          and s.skuId = :skuId
          and s.locationId = :locationId
          and s.status = 'AVAILABLE'
        """)
    List<Stock> lockAvailableAtLocation(
            @Param("warehouseId") UUID warehouseId,
            @Param("skuId") UUID skuId,
            @Param("locationId") UUID locationId);
}
