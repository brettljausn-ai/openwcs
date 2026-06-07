package org.openwcs.flow.repo;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.ConveyorLoop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConveyorLoopRepository extends JpaRepository<ConveyorLoop, UUID> {
    List<ConveyorLoop> findByWarehouseId(UUID warehouseId);

    Optional<ConveyorLoop> findByWarehouseIdAndCode(UUID warehouseId, String code);

    /**
     * Like {@link #findByWarehouseIdAndCode} but takes a row-level write lock on the loop. Routing
     * uses this on the loop-entry path so the occupancy count and the decision to enter are atomic:
     * concurrent scans entering the SAME loop (including across horizontally-scaled replicas)
     * serialize on this lock, so the capacity limit can't be exceeded by a check-then-act race.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ConveyorLoop l where l.warehouseId = :warehouseId and l.code = :code")
    Optional<ConveyorLoop> lockByWarehouseIdAndCode(@Param("warehouseId") UUID warehouseId,
                                                    @Param("code") String code);

    void deleteByWarehouseId(UUID warehouseId);
}
