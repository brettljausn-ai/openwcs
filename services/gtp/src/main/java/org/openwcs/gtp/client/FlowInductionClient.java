package org.openwcs.gtp.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read/write seam onto flow-orchestrator's induction queue (ADR-0007 §3). Since 3c-1 the inbound
 * presentation queue ({@code REQUESTED → IN_TRANSIT → QUEUED → DONE}) is owned by flow, not gtp: the
 * workstation screen reads the slice from here, and operator completion fans the entry out to flow's
 * DONE endpoint (after which gtp runs its own store-back).
 */
public interface FlowInductionClient {

    /**
     * The inbound pipeline slice for a workplace ({@code REQUESTED, IN_TRANSIT, QUEUED}; DONE
     * excluded). Mirrors {@code GET /api/flow/induction/queue?workplaceId=...} (§3.2). Ordering is
     * decided by flow (QUEUED first by arrival_seq, then IN_TRANSIT, then REQUESTED).
     */
    List<InductionEntry> readQueue(UUID workplaceId);

    /** A single induction entry by id, or {@code null} if it does not exist. */
    InductionEntry getEntry(UUID entryId);

    /**
     * Mark an entry DONE (§3.3). Idempotent on the flow side. Returns the (now DONE) entry, or
     * {@code null} if the call did not yield one.
     */
    InductionEntry markDone(UUID entryId);

    /**
     * The fields of a flow induction entry that gtp needs (queue display, store-back, exceptions).
     * Mirrors flow's {@code InductionEntryView} (§3.1 response shape).
     */
    record InductionEntry(
            UUID id,
            UUID warehouseId,
            UUID workplaceId,
            UUID huId,
            String huCode,
            UUID skuId,
            String skuCode,
            BigDecimal qty,
            String mode,
            String status,
            Long arrivalSeq,
            java.time.Instant requestedAt,
            java.time.Instant inTransitAt,
            java.time.Instant queuedAt,
            UUID countTaskId,
            UUID countLineId,
            UUID locationId) {
    }
}
