package org.openwcs.slotting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Idempotency inbox for the velocity consumer (mirrors {@code inventory.processed_event},
 * build.md §5.5). The presence of an {@code eventId} means the velocity projection has already
 * counted that transaction-log event, so a redelivery or replay of the same event is skipped.
 */
@Entity
@Table(name = "velocity_processed_event")
public class VelocityProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "stream_id")
    private String streamId;

    @Column(name = "seq")
    private Long seq;

    @CreationTimestamp
    @Column(name = "applied_at", nullable = false, updatable = false)
    private Instant appliedAt;

    protected VelocityProcessedEvent() {
    }

    public VelocityProcessedEvent(UUID eventId, String eventType, String streamId, Long seq) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.streamId = streamId;
        this.seq = seq;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getStreamId() {
        return streamId;
    }

    public Long getSeq() {
        return seq;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
