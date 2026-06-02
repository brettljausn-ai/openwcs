package org.openwcs.txlog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable entry in the transaction log (build.md §5.2). Append-only: there are
 * no setters for a mutation path and the database blocks UPDATE/DELETE. Corrections
 * are new compensating events.
 */
@Entity
@Table(name = "events")
public class Event {

    /** Global total order for replay (DB-assigned bigserial; read-only). */
    @Column(name = "position", insertable = false, updatable = false)
    private Long position;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    @Column(name = "stream_id", updatable = false, nullable = false)
    private String streamId;

    @Column(name = "seq", updatable = false, nullable = false)
    private long seq;

    @Column(name = "event_type", updatable = false, nullable = false)
    private String eventType;

    @Column(name = "occurred_at", updatable = false, nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", updatable = false, nullable = false)
    private Instant recordedAt;

    @Column(name = "actor", updatable = false, nullable = false)
    private String actor;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, nullable = false)
    private Map<String, Object> payload;

    @Column(name = "payload_version", updatable = false, nullable = false)
    private int payloadVersion = 1;

    protected Event() {
    }

    public Event(String streamId, long seq, String eventType, Instant occurredAt, Instant recordedAt,
                 String actor, UUID correlationId, Map<String, Object> payload, int payloadVersion) {
        this.streamId = streamId;
        this.seq = seq;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.actor = actor;
        this.correlationId = correlationId;
        this.payload = payload;
        this.payloadVersion = payloadVersion;
    }

    public Long getPosition() {
        return position;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getStreamId() {
        return streamId;
    }

    public long getSeq() {
        return seq;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public String getActor() {
        return actor;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public int getPayloadVersion() {
        return payloadVersion;
    }
}
