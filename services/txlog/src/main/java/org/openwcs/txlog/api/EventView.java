package org.openwcs.txlog.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.openwcs.txlog.domain.Event;

/** Read model returned by the query/replay endpoints. */
public record EventView(
        Long position,
        UUID eventId,
        String streamId,
        long seq,
        String eventType,
        Instant occurredAt,
        Instant recordedAt,
        String actor,
        UUID correlationId,
        Map<String, Object> payload,
        int payloadVersion) {

    public static EventView from(Event e) {
        return new EventView(
                e.getPosition(), e.getEventId(), e.getStreamId(), e.getSeq(), e.getEventType(),
                e.getOccurredAt(), e.getRecordedAt(), e.getActor(), e.getCorrelationId(),
                e.getPayload(), e.getPayloadVersion());
    }
}
