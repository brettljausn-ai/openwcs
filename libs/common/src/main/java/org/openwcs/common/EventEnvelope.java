package org.openwcs.common;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared envelope for every domain event placed on the Kafka backbone.
 * Carries identity, ordering, and correlation metadata; the typed payload
 * is service-specific. See build.md §5.2 (transaction log) and §9 (events).
 */
public record EventEnvelope(
        UUID eventId,
        String streamId,
        long seq,
        String eventType,
        Instant occurredAt,
        Instant recordedAt,
        String actor,
        UUID correlationId,
        int payloadVersion,
        Object payload) {
}
