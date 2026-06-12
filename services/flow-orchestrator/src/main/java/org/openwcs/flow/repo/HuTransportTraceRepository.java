package org.openwcs.flow.repo;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.domain.HuTransportTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HuTransportTraceRepository extends JpaRepository<HuTransportTrace, UUID> {

    /** An HU's transport timeline, ts ASC (timeline order). */
    List<HuTransportTrace> findByHuIdOrderByTsAsc(UUID huId);

    /** An HU's transport timeline scoped to a warehouse, ts ASC. */
    List<HuTransportTrace> findByWarehouseIdAndHuIdOrderByTsAsc(UUID warehouseId, UUID huId);

    /** All trace rows for a warehouse (used by the demo full-reset clear). */
    List<HuTransportTrace> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from HuTransportTrace e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
