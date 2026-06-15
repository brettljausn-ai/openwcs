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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Pick queue: OUTBOUND orders carrying lines that are released/allocated and still need
     * picking. An order qualifies when it is in a released, fulfillable state (ALLOCATED or
     * PARTIALLY_ALLOCATED). The fetch join pulls the lines; each line's transactions load
     * lazily within the read transaction when the read model computes picked qty + the
     * best-effort pick location (a second bag cannot be fetch-joined in the same query).
     * Ordered for a sensible pick walk: most-urgent order first (priority, then cut-off),
     * then order reference; the service filters to lines still needing picking.
     */
    @Query("""
        select distinct o from OutboundOrder o
        join fetch o.lines l
        where o.warehouseId = :warehouseId
          and o.orderType = org.openwcs.orders.domain.OrderType.OUTBOUND
          and o.status in (org.openwcs.orders.domain.OrderStatus.ALLOCATED,
                           org.openwcs.orders.domain.OrderStatus.PARTIALLY_ALLOCATED)
          and l.status in (org.openwcs.orders.domain.LineStatus.ALLOCATED,
                           org.openwcs.orders.domain.LineStatus.SHORT)
        order by o.priority desc, o.dispatchBy asc nulls last, o.orderRef asc
        """)
    List<OutboundOrder> pickQueue(@Param("warehouseId") UUID warehouseId);

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

    /**
     * Roll-up of the outbound orders assigned to one dispatch wave (warehouse + route + cut-off),
     * for advancing the persisted {@link org.openwcs.orders.domain.RouteDispatch}. Excludes
     * CANCELLED orders. {@code assigned} = orders on the route, {@code ready} = the ALLOCATED /
     * PARTIALLY_ALLOCATED / SHIPPED subset (allocated or already gone), {@code shipped} = SHIPPED.
     */
    @Query("""
        select count(o) as assigned,
               sum(case when o.status in (org.openwcs.orders.domain.OrderStatus.ALLOCATED,
                                          org.openwcs.orders.domain.OrderStatus.PARTIALLY_ALLOCATED,
                                          org.openwcs.orders.domain.OrderStatus.SHIPPED)
                        then 1 else 0 end) as ready,
               sum(case when o.status = org.openwcs.orders.domain.OrderStatus.SHIPPED
                        then 1 else 0 end) as shipped
        from OutboundOrder o
        where o.warehouseId = :warehouseId
          and o.orderType = org.openwcs.orders.domain.OrderType.OUTBOUND
          and o.routeCode = :routeCode
          and o.dispatchBy = :cutoff
          and o.status <> org.openwcs.orders.domain.OrderStatus.CANCELLED
        """)
    RouteDispatchRollup routeDispatchRollup(
            @Param("warehouseId") UUID warehouseId,
            @Param("routeCode") String routeCode,
            @Param("cutoff") Instant cutoff);

    /** Projection for {@link #routeDispatchRollup}: assigned / ready / shipped order counts. */
    interface RouteDispatchRollup {
        long getAssigned();

        long getReady();

        long getShipped();
    }

    /**
     * Bulk-delete every order of a warehouse in one DELETE statement (demo-mode reset).
     * Lines, line transactions and their outbox rows cascade at the DB level
     * (ON DELETE CASCADE), so no child loading or ordering is needed.
     */
    @Modifying
    @Query("delete from OutboundOrder o where o.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
