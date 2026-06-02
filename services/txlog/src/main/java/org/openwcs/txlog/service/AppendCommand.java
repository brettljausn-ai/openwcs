package org.openwcs.txlog.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Request to append one event to the transaction log. {@code occurredAt},
 * {@code payloadVersion} and {@code expectedSeq} are optional; a null
 * {@code expectedSeq} auto-assigns the next per-stream sequence, while a supplied
 * value enforces optimistic concurrency via the (stream_id, seq) uniqueness guarantee.
 */
public record AppendCommand(
        String streamId,
        String eventType,
        Instant occurredAt,
        String actor,
        UUID correlationId,
        Map<String, Object> payload,
        Integer payloadVersion,
        Long expectedSeq) {
}
