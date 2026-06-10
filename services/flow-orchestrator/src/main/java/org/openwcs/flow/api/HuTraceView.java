package org.openwcs.flow.api;

import java.time.Instant;
import java.util.UUID;
import org.openwcs.flow.domain.HuTransportTrace;

/** Read model for an HU transport-trace row (ADR-0007 §3.4). */
public record HuTraceView(
        UUID id,
        UUID huId,
        String huCode,
        Instant ts,
        String point,
        String event,
        String decision,
        String fromPoint,
        String toPoint,
        UUID workplaceId,
        UUID correlationId,
        UUID taskId,
        UUID inductionEntryId) {

    public static HuTraceView from(HuTransportTrace t) {
        return new HuTraceView(t.getId(), t.getHuId(), t.getHuCode(), t.getTs(), t.getPoint(),
                t.getEvent(), t.getDecision(), t.getFromPoint(), t.getToPoint(), t.getWorkplaceId(),
                t.getCorrelationId(), t.getTaskId(), t.getInductionEntryId());
    }
}
