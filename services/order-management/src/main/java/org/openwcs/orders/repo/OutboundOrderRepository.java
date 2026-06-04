package org.openwcs.orders.repo;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.orders.domain.OrderStatus;
import org.openwcs.orders.domain.OutboundOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboundOrderRepository extends JpaRepository<OutboundOrder, UUID> {
    Optional<OutboundOrder> findByOrderRef(String orderRef);

    Page<OutboundOrder> findByWarehouseId(UUID warehouseId, Pageable pageable);

    /** All orders for a warehouse (any type/status) — used by the demo full-reset clear. */
    List<OutboundOrder> findByWarehouseId(UUID warehouseId);

    Page<OutboundOrder> findByWarehouseIdAndStatus(UUID warehouseId, OrderStatus status, Pageable pageable);

    /** Release queue (OUTBOUND only): most urgent first — highest priority, then earliest dispatch. */
    @Query("""
        select o from OutboundOrder o
        where o.warehouseId = :warehouseId
          and o.orderType = org.openwcs.orders.domain.OrderType.OUTBOUND
          and o.status in :statuses
        order by o.priority desc, o.dispatchBy asc nulls last
        """)
    List<OutboundOrder> releaseQueue(
            @Param("warehouseId") UUID warehouseId,
            @Param("statuses") Collection<OrderStatus> statuses);

    /** CREATED OUTBOUND orders due by the cut-off (null dispatch time = always due), most urgent first. */
    @Query("""
        select o from OutboundOrder o
        where o.warehouseId = :warehouseId
          and o.orderType = org.openwcs.orders.domain.OrderType.OUTBOUND
          and o.status = org.openwcs.orders.domain.OrderStatus.CREATED
          and (o.dispatchBy is null or o.dispatchBy <= :cutoff)
        order by o.priority desc, o.dispatchBy asc nulls last
        """)
    List<OutboundOrder> dueForRelease(
            @Param("warehouseId") UUID warehouseId,
            @Param("cutoff") Instant cutoff);
}
