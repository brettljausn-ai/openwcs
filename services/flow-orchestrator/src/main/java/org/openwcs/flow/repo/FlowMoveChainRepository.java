package org.openwcs.flow.repo;

import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.FlowMoveChain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for cross-system move chains (RETRIEVE → CONVEY → STORE). */
public interface FlowMoveChainRepository extends JpaRepository<FlowMoveChain, UUID> {

    /** Lifecycle wiring: find the chain a completing leg's device task belongs to. */
    Optional<FlowMoveChain> findByRetrieveTaskId(UUID retrieveTaskId);

    Optional<FlowMoveChain> findByConveyTaskId(UUID conveyTaskId);

    Optional<FlowMoveChain> findByStoreTaskId(UUID storeTaskId);

    /** Bulk-delete all chains for a warehouse (demo-mode reset). */
    @Modifying
    @Query("delete from FlowMoveChain c where c.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
