package org.openwcs.processdesigner.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One run of a process by an operator, pinned to the definition version it started on (publishing
 * a new active version never mutates a running instance). The data object + current step are
 * checkpointed at each task step so a device swap / reload resumes mid-flow.
 */
@Entity
@Table(name = "process_instance")
public class ProcessInstance {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "process_key", nullable = false, updatable = false)
    private String processKey;

    @Column(name = "def_version", nullable = false, updatable = false)
    private int defVersion;

    @Column(name = "status", nullable = false)
    private String status = RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", nullable = false)
    private JsonNode data;

    @Column(name = "current_step")
    private String currentStep;

    @Column(name = "started_by")
    private String startedBy;

    @Column(name = "warehouse_id")
    private UUID warehouseId;

    @CreationTimestamp
    @Column(name = "started_at", updatable = false, nullable = false)
    private Instant startedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    public UUID getId() {
        return id;
    }

    public String getProcessKey() {
        return processKey;
    }

    public void setProcessKey(String processKey) {
        this.processKey = processKey;
    }

    public int getDefVersion() {
        return defVersion;
    }

    public void setDefVersion(int defVersion) {
        this.defVersion = defVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public void setStartedBy(String startedBy) {
        this.startedBy = startedBy;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
