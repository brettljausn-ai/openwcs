package org.openwcs.txlog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Transactional-outbox row (build.md §5.5). Persisted in the same transaction as the
 * {@link Event} append; the {@code OutboxRelay} publishes unsent rows to Kafka and
 * stamps {@code publishedAt}.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "topic", nullable = false, updatable = false)
    private String topic;

    @Column(name = "message_key", updatable = false)
    private String messageKey;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxMessage() {
    }

    public OutboxMessage(UUID eventId, String topic, String messageKey, String payload) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
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

    public UUID getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }
}
