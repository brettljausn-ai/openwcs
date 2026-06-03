package org.openwcs.gtp.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.TaskLine;
import org.openwcs.gtp.domain.WorkCycle;

/**
 * A work cycle and its work lines. For PICKING the lines are the {@code puts} put-list (the
 * goods-to-person batch from one presented stock HU); for the other operating modes they are the
 * mode-appropriate {@code taskLines} (decant-moves, count entries, QC verdicts, maintenance checks).
 * {@code mode} echoes the station's destination topology; {@code operatingMode} is what this cycle
 * runs.
 */
public record WorkCycleView(
        UUID id,
        UUID stationId,
        String operatingMode,
        UUID stockNodeId,
        UUID stockHuId,
        UUID targetHuId,
        UUID skuId,
        String mode,
        BigDecimal presentedQty,
        BigDecimal remainingQty,
        String status,
        List<PutInstructionView> puts,
        List<TaskLineView> taskLines) {

    public static WorkCycleView from(WorkCycle c, String mode, List<PutInstruction> puts,
                                     List<TaskLine> taskLines) {
        return new WorkCycleView(c.getId(), c.getStationId(), c.getOperatingMode(),
                c.getStockNodeId(), c.getStockHuId(), c.getTargetHuId(), c.getSkuId(), mode,
                c.getPresentedQty(), c.getRemainingQty(), c.getStatus(),
                puts.stream().map(PutInstructionView::from).toList(),
                taskLines.stream().map(TaskLineView::from).toList());
    }
}
