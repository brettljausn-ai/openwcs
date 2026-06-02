package org.openwcs.txlog.api;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.openwcs.txlog.service.AppendCommand;

/** Request body for appending an event (build.md §5.2). */
public record AppendEventRequest(
        @NotBlank String streamId,
        @NotBlank String eventType,
        Instant occurredAt,
        @NotBlank String actor,
        UUID correlationId,
        Map<String, Object> payload,
        Integer payloadVersion,
        Long expectedSeq) {

    public AppendCommand toCommand() {
        return new AppendCommand(
                streamId, eventType, occurredAt, actor, correlationId, payload, payloadVersion, expectedSeq);
    }
}
