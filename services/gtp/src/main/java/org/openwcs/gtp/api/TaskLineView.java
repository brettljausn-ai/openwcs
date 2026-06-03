package org.openwcs.gtp.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.gtp.domain.TaskLine;

/** One mode-appropriate task line of a non-PICKING cycle and its outcome state. */
public record TaskLineView(
        UUID id,
        String lineType,
        UUID huId,
        UUID skuId,
        String compartment,
        BigDecimal expectedQty,
        BigDecimal actualQty,
        BigDecimal variance,
        String verdict,
        String putLightId,
        String status) {

    public static TaskLineView from(TaskLine t) {
        return new TaskLineView(t.getId(), t.getLineType(), t.getHuId(), t.getSkuId(),
                t.getCompartment(), t.getExpectedQty(), t.getActualQty(), t.getVariance(),
                t.getVerdict(), t.getPutLightId(), t.getStatus());
    }
}
