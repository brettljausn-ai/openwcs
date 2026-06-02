package org.openwcs.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Local outbox row written in the same transaction as an {@link OrderLineTransaction}
 * (build.md §5.5). The relay appends the event to the transaction log and stamps
 * {@code publishedAt}; {@code lineTxnId} links back so the relay can record the event id.
 */
@Entity
@Table(name = "order_outbox")
public class OrderOutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "line_txn_id", nullable = false, updatable = false)
    private UUID lineTxnId;

    @Column(name = "stream_id", nullable = false, updatable = false)
    private String streamId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "actor", nullable = false, updatable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OrderOutboxMessage() {
    }

    public OrderOutboxMessage(UUID lineTxnId, String streamId, String eventType,
                              UUID correlationId, String actor, Map<String, Object> payload) {
        this.lineTxnId = lineTxnId;
        this.streamId = streamId;
        this.eventType = eventType;
        this.correlationId = correlationId;
        this.actor = actor;
        this.payload = payload;
    }

    public void markPublished(Instant when) {
        this.publishedAt = when;
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public Long getId() {
        return id;
    }

    public UUID getLineTxnId() {
        return lineTxnId;
    }

    public String getStreamId() {
        return streamId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public String getActor() {
        return actor;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
