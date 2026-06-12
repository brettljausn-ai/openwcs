package org.openwcs.flow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.openwcs.flow.domain.InductionQueueEntry;

/** Read model for an induction queue entry (ADR-0007 §3.1 response shape). */
public record InductionEntryView(
        UUID id,
        UUID warehouseId,
        UUID workplaceId,
        String workplaceKind,
        UUID huId,
        String huCode,
        UUID skuId,
        String skuCode,
        BigDecimal qty,
        String mode,
        String status,
        Long arrivalSeq,
        Instant requestedAt,
        Instant inTransitAt,
        Instant queuedAt,
        Instant doneAt,
        UUID retrieveTaskId,
        UUID relocateTaskId,
        UUID conveyTaskId,
        UUID returnConveyTaskId,
        UUID returnStoreTaskId,
        UUID countTaskId,
        UUID countLineId,
        UUID locationId,
        UUID storageLocationId,
        boolean awaitingSlot) {

    public static InductionEntryView from(InductionQueueEntry e) {
        return new InductionEntryView(
                e.getId(), e.getWarehouseId(), e.getWorkplaceId(), e.getWorkplaceKind(),
                e.getHuId(), e.getHuCode(), e.getSkuId(), e.getSkuCode(), e.getQty(), e.getMode(),
                e.getStatus(), e.getArrivalSeq(), e.getRequestedAt(), e.getInTransitAt(),
                e.getQueuedAt(), e.getDoneAt(), e.getRetrieveTaskId(), e.getRelocateTaskId(),
                e.getConveyTaskId(), e.getReturnConveyTaskId(), e.getReturnStoreTaskId(),
                e.getCountTaskId(), e.getCountLineId(), e.getLocationId(),
                e.getStorageLocationId(), e.isAwaitingSlot());
    }
}
