package org.openwcs.flow.repo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.flow.domain.InductionQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InductionQueueEntryRepository extends JpaRepository<InductionQueueEntry, UUID> {

    /** Lifecycle wiring: find the entry a completing RETRIEVE/CONVEY device task belongs to. */
    Optional<InductionQueueEntry> findByRetrieveTaskId(UUID retrieveTaskId);

    Optional<InductionQueueEntry> findByConveyTaskId(UUID conveyTaskId);

    /** Dig-out wiring (ADR-0009): find the entry a completing RELOCATE device task belongs to. */
    Optional<InductionQueueEntry> findByRelocateTaskId(UUID relocateTaskId);

    /** Return-leg wiring: find the entry a completing return CONVEY / STORE device task belongs to. */
    Optional<InductionQueueEntry> findByReturnConveyTaskId(UUID returnConveyTaskId);

    Optional<InductionQueueEntry> findByReturnStoreTaskId(UUID returnStoreTaskId);

    /** Live-scan trace (ADR-0008 §3): the most recent transport a scanned HU barcode belongs to. */
    Optional<InductionQueueEntry> findFirstByWarehouseIdAndHuCodeOrderByRequestedAtDesc(UUID warehouseId,
                                                                                        String huCode);

    /** Cap counting: how many {IN_TRANSIT, QUEUED} entries a workplace holds (optionally by mode). */
    long countByWorkplaceIdAndStatusIn(UUID workplaceId, List<String> statuses);

    long countByWorkplaceIdAndModeInAndStatusIn(UUID workplaceId, List<String> modes, List<String> statuses);

    /** REQUESTED backlog for a workplace, oldest first, for re-metering retrievals. */
    List<InductionQueueEntry> findByWorkplaceIdAndStatusOrderByRequestedAtAsc(UUID workplaceId, String status);

    /** Highest arrival sequence assigned so far for a workplace (NULL when none QUEUED yet). */
    @Query("select max(e.arrivalSeq) from InductionQueueEntry e where e.workplaceId = :workplaceId")
    Long maxArrivalSeq(@Param("workplaceId") UUID workplaceId);

    /**
     * The whole inbound pipeline for a workplace, DONE excluded: QUEUED first (arrival_seq ASC),
     * then IN_TRANSIT (in_transit_at ASC), then REQUESTED (requested_at ASC). NULLS are ordered
     * last so they never sort ahead of a real value.
     */
    @Query("select e from InductionQueueEntry e where e.workplaceId = :workplaceId and e.status <> 'DONE'"
            + " order by case e.status when 'QUEUED' then 0 when 'IN_TRANSIT' then 1 else 2 end asc,"
            + " e.arrivalSeq asc nulls last, e.inTransitAt asc nulls last, e.requestedAt asc")
    List<InductionQueueEntry> findActiveSlice(@Param("workplaceId") UUID workplaceId);

    /** A single-status slice for a workplace (e.g. only the workable QUEUED head), ordered. */
    @Query("select e from InductionQueueEntry e where e.workplaceId = :workplaceId and e.status = :status"
            + " order by e.arrivalSeq asc nulls last, e.inTransitAt asc nulls last, e.requestedAt asc")
    List<InductionQueueEntry> findSliceByStatus(@Param("workplaceId") UUID workplaceId,
                                                @Param("status") String status);

    /** Return legs waiting for slotting to answer (awaiting-slot retry sweep). */
    List<InductionQueueEntry> findByAwaitingSlotTrue();

    /** All entries for a warehouse (used by the demo full-reset clear). */
    List<InductionQueueEntry> findByWarehouseId(UUID warehouseId);

    /** Bulk-delete all rows for a warehouse in one DELETE statement (demo-mode reset). */
    @Modifying
    @Query("delete from InductionQueueEntry e where e.warehouseId = :warehouseId")
    int deleteBulkByWarehouseId(@Param("warehouseId") UUID warehouseId);
}
