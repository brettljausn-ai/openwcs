package org.openwcs.gtp.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.gtp.domain.DestinationDemand;

/** Open/closed per-destination demand. */
public record DemandView(
        UUID id,
        UUID stationNodeId,
        String orderRef,
        UUID orderLineId,
        UUID skuId,
        BigDecimal requestedQty,
        BigDecimal puttedQty,
        BigDecimal remainingQty,
        String status) {

    public static DemandView from(DestinationDemand d) {
        return new DemandView(d.getId(), d.getStationNodeId(), d.getOrderRef(), d.getOrderLineId(),
                d.getSkuId(), d.getRequestedQty(), d.getPuttedQty(), d.remaining(), d.getStatus());
    }
}
