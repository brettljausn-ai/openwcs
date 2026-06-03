package org.openwcs.gtp.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.gtp.domain.PutInstruction;

/** One line of a put-list / its confirmation state. */
public record PutInstructionView(
        UUID id,
        UUID destinationNodeId,
        UUID destinationDemandId,
        String orderRef,
        UUID orderLineId,
        UUID orderHuId,
        String putLightId,
        BigDecimal qty,
        BigDecimal confirmedQty,
        String status) {

    public static PutInstructionView from(PutInstruction p) {
        return new PutInstructionView(p.getId(), p.getDestinationNodeId(), p.getDestinationDemandId(),
                p.getOrderRef(), p.getOrderLineId(), p.getOrderHuId(), p.getPutLightId(),
                p.getQty(), p.getConfirmedQty(), p.getStatus());
    }
}
