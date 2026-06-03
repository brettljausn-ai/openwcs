package org.openwcs.counting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A scheduled or ad-hoc count over a scope (a location / SKU / zone / block).
 * {@code countType} BLIND hides the expected qty from the operator; VARIANCE shows it.
 * Lifecycle: OPEN → COUNTED → RECONCILED, or → RECOUNT (which spawns a follow-up task).
 * A task may be executed at a GTP station in STOCK_COUNT mode and/or be orchestrated by the
 * cycle-count BPMN — both referenced by id only (seams; not wired here).
 */
@Entity
@Table(name = "count_task")
public class CountTask extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "count_task_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** LOCATION | SKU | ZONE | BLOCK. */
    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    /** The counted location/sku/zone/block id. */
    @Column(name = "scope_ref")
    private UUID scopeRef;

    /** BLIND | VARIANCE. */
    @Column(name = "count_type", nullable = false)
    private String countType = "BLIND";

    /** AD_HOC | SCHEDULED | RECOUNT. */
    @Column(name = "origin", nullable = false)
    private String origin = "AD_HOC";

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "parent_task_id")
    private UUID parentTaskId;

    @Column(name = "tolerance", nullable = false)
    private BigDecimal tolerance = BigDecimal.ZERO;

    /** Seam: the GTP station executing this task in STOCK_COUNT mode (referenced by id only). */
    @Column(name = "gtp_station_id")
    private UUID gtpStationId;

    /** Seam: the cycle-count BPMN process instance orchestrating this task (referenced by id only). */
    @Column(name = "process_instance_id")
    private String processInstanceId;

    /** OPEN | COUNTED | RECONCILED | RECOUNT. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    @Column(name = "assigned_to")
    private String assignedTo;

    @Column(name = "counted_by")
    private String countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "reconciled_by")
    private String reconciledBy;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public UUID getScopeRef() {
        return scopeRef;
    }

    public void setScopeRef(UUID scopeRef) {
        this.scopeRef = scopeRef;
    }

    public String getCountType() {
        return countType;
    }

    public void setCountType(String countType) {
        this.countType = countType;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public void setScheduleId(UUID scheduleId) {
        this.scheduleId = scheduleId;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public void setParentTaskId(UUID parentTaskId) {
        this.parentTaskId = parentTaskId;
    }

    public BigDecimal getTolerance() {
        return tolerance;
    }

    public void setTolerance(BigDecimal tolerance) {
        this.tolerance = tolerance;
    }

    public UUID getGtpStationId() {
        return gtpStationId;
    }

    public void setGtpStationId(UUID gtpStationId) {
        this.gtpStationId = gtpStationId;
    }

    public String getProcessInstanceId() {
        return processInstanceId;
    }

    public void setProcessInstanceId(String processInstanceId) {
        this.processInstanceId = processInstanceId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(String assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getCountedBy() {
        return countedBy;
    }

    public void setCountedBy(String countedBy) {
        this.countedBy = countedBy;
    }

    public Instant getCountedAt() {
        return countedAt;
    }

    public void setCountedAt(Instant countedAt) {
        this.countedAt = countedAt;
    }

    public String getReconciledBy() {
        return reconciledBy;
    }

    public void setReconciledBy(String reconciledBy) {
        this.reconciledBy = reconciledBy;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public void setReconciledAt(Instant reconciledAt) {
        this.reconciledAt = reconciledAt;
    }
}
