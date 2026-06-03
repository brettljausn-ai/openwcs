package org.openwcs.gtp.api;

import java.time.Instant;
import java.util.UUID;
import org.openwcs.gtp.domain.WorkplaceSession;

/**
 * A claimed workplace session plus its work context (the workplace it owns). Returned from a claim
 * so the console knows its {@code sessionId} and can render the workplace immediately.
 */
public record WorkplaceSessionView(
        UUID sessionId,
        UUID stationId,
        String operator,
        String status,
        Instant claimedAt,
        Instant lastHeartbeatAt,
        WorkplaceView workplace) {

    public static WorkplaceSessionView from(WorkplaceSession s, WorkplaceView workplace) {
        return new WorkplaceSessionView(s.getId(), s.getStationId(), s.getOperator(), s.getStatus(),
                s.getClaimedAt(), s.getLastHeartbeatAt(), workplace);
    }
}
