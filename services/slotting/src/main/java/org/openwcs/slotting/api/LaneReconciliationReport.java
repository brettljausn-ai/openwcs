package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;

/**
 * Result of a lane-depth ledger reconciliation (ADR 0003 fast-follow): how many multi-deep lanes
 * were checked, how many cells the stored ledger had marked occupied that inventory says are empty
 * and were therefore corrected, and the per-lane discrepancies between slotting's assumed occupied
 * depth and inventory's actual occupied depth.
 */
public record LaneReconciliationReport(
        int lanesChecked,
        int corrected,
        List<Discrepancy> discrepancies) {

    /**
     * One multi-deep lane whose slotting-assumed occupied depth differs from inventory truth.
     *
     * @param blockId      the storage block the lane belongs to
     * @param laneKey      stable lane identity (aisle / level / column) within the block
     * @param locationId   a representative cell of the lane (the aisle-face cell where known)
     * @param expectedDepth occupied depth slotting assumed from its ledger
     * @param actualDepth   occupied depth inventory actually reports for the lane's cells
     */
    public record Discrepancy(
            UUID blockId,
            String laneKey,
            UUID locationId,
            int expectedDepth,
            int actualDepth) {
    }
}
