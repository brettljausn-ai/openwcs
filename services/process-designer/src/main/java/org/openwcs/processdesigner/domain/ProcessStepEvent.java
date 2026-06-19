package org.openwcs.processdesigner.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ordered, append-only entry in an instance's step history (spec: per-screen step history for
 * exact resume + replay). Each row records a single advance: a screen step the mobile client posts,
 * a task-step checkpoint, or an audit event (e.g. a reassign). The {@code seq} is the monotonic
 * position within the instance, made unique by {@code (instanceId, seq)} so a re-posted advance is
 * idempotent. {@code data} is the merged data object as of that advance, {@code enteredBy} the
 * forwarded operator who posted it.
 */
@Entity
@Table(name = "process_step_event")
public class ProcessStepEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private UUID instanceId;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Column(name = "step_id")
    private String stepId;

    @Column(name = "step_type")
    private String stepType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    private JsonNode data;

    @Column(name = "entered_by")
    private String enteredBy;

    @CreationTimestamp
    @Column(name = "at", updatable = false, nullable = false)
    private Instant at;

    public ProcessStepEvent() {
    }

    public ProcessStepEvent(UUID instanceId, long seq, String stepId, String stepType, JsonNode data,
                            String enteredBy) {
        this.instanceId = instanceId;
        this.seq = seq;
        this.stepId = stepId;
        this.stepType = stepType;
        this.data = data;
        this.enteredBy = enteredBy;
    }

    public UUID getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public long getSeq() {
        return seq;
    }

    public String getStepId() {
        return stepId;
    }

    public String getStepType() {
        return stepType;
    }

    public JsonNode getData() {
        return data;
    }

    public String getEnteredBy() {
        return enteredBy;
    }

    public Instant getAt() {
        return at;
    }
}
