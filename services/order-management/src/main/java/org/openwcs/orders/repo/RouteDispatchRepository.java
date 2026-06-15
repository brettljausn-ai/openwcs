package org.openwcs.orders.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.orders.domain.RouteDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the {@link RouteDispatch} dispatch waves backing the dispatch board. */
public interface RouteDispatchRepository extends JpaRepository<RouteDispatch, UUID> {

    /** The wave for a (warehouse, route, cut-off) key, if it has already been opened. */
    Optional<RouteDispatch> findByWarehouseIdAndRouteCodeAndCutoff(
            UUID warehouseId, String routeCode, Instant cutoff);

    /** All waves of a warehouse, soonest cut-off first — drives the dispatch board ordering. */
    @Query("""
        select d from RouteDispatch d
        where d.warehouseId = :warehouseId
        order by d.cutoff asc, d.routeCode asc
        """)
    List<RouteDispatch> board(@Param("warehouseId") UUID warehouseId);

    /** Drop every dispatch wave of a warehouse in one statement (demo-mode reset). */
    @Modifying
    @Query("delete from RouteDispatch d where d.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
