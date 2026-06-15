package org.openwcs.allocation.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.allocation.domain.OrderAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderAllocationRepository extends JpaRepository<OrderAllocation, UUID> {
    Optional<OrderAllocation> findByOrderRef(String orderRef);

    /**
     * All non-cancelled order allocations of a warehouse with their lines eagerly fetched
     * (stock-blocking report). CANCELLED orders no longer hold demand, so they are excluded.
     */
    @Query("select distinct a from OrderAllocation a left join fetch a.lines"
            + " where a.warehouseId = :warehouseId and a.status <> 'CANCELLED'")
    List<OrderAllocation> findOpenWithLinesByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
