package org.openwcs.processdesigner.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The idempotency ledger for task-step checkpoints. A {@code (instanceId, stepId)} task step is
 * executed at most once: the merged data object after the task ran is stored here, so re-posting
 * the same checkpoint returns the stored result without re-running the task's side effects.
 */
@Entity
@Table(name = "process_step_result")
@IdClass(ProcessStepResult.Key.class)
public class ProcessStepResult {

    @Id
    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Id
    @Column(name = "step_id", nullable = false)
    private String stepId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", nullable = false)
    private JsonNode resultData;

    @Column(name = "next_step")
    private String nextStep;

    @Column(name = "done", nullable = false)
    private boolean done;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public ProcessStepResult() {
    }

    public ProcessStepResult(UUID instanceId, String stepId, JsonNode resultData, String nextStep, boolean done) {
        this.instanceId = instanceId;
        this.stepId = stepId;
        this.resultData = resultData;
        this.nextStep = nextStep;
        this.done = done;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getStepId() {
        return stepId;
    }

    public JsonNode getResultData() {
        return resultData;
    }

    public String getNextStep() {
        return nextStep;
    }

    public boolean isDone() {
        return done;
    }

    /** Composite primary key (instanceId, stepId). */
    public static class Key implements Serializable {
        private UUID instanceId;
        private String stepId;

        public Key() {
        }

        public Key(UUID instanceId, String stepId) {
            this.instanceId = instanceId;
            this.stepId = stepId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(instanceId, key.instanceId) && Objects.equals(stepId, key.stepId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(instanceId, stepId);
        }
    }
}
