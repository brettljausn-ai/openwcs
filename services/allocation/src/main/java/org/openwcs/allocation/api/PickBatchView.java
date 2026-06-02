package org.openwcs.allocation.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.allocation.domain.BatchMember;
import org.openwcs.allocation.domain.MergedPickLine;
import org.openwcs.allocation.domain.PickBatch;

/** Read model for a pick batch: the combined pick list + per-order separation plan. */
public record PickBatchView(
        UUID id,
        UUID warehouseId,
        UUID pickToteShipperId,
        String status,
        List<BatchMember> members,
        List<MergedPickLine> pickLines) {

    public static PickBatchView from(PickBatch b) {
        return new PickBatchView(b.getId(), b.getWarehouseId(), b.getPickToteShipperId(),
                b.getStatus(), b.getMembers(), b.getPickLines());
    }
}
