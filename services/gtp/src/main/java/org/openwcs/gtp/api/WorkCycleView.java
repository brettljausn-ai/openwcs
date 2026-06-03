package org.openwcs.gtp.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.WorkCycle;

/** A work cycle and its put-list (the goods-to-person batch from one presented stock HU). */
public record WorkCycleView(
        UUID id,
        UUID stationId,
        UUID stockNodeId,
        UUID stockHuId,
        UUID skuId,
        String mode,
        BigDecimal presentedQty,
        BigDecimal remainingQty,
        String status,
        List<PutInstructionView> puts) {

    public static WorkCycleView from(WorkCycle c, String mode, List<PutInstruction> puts) {
        return new WorkCycleView(c.getId(), c.getStationId(), c.getStockNodeId(), c.getStockHuId(),
                c.getSkuId(), mode, c.getPresentedQty(), c.getRemainingQty(), c.getStatus(),
                puts.stream().map(PutInstructionView::from).toList());
    }
}
