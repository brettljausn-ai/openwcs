package org.openwcs.flow.domain;

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
 * Append-only per-HU transport timeline (ADR-0007 §2.2, R4). One row is written at each induction
 * lifecycle transition (REQUESTED, RETRIEVED, INDUCTED, ARRIVED, QUEUED, DONE). Rows are never
 * mutated, so this entity deliberately does <em>not</em> extend {@link Auditable} (no
 * {@code updated_at} / {@code version}) — it carries only a creation timestamp.
 */
@Entity
@Table(name = "hu_transport_trace")
public class HuTransportTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "trace_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id", nullable = false)
    private UUID huId;

    @Column(name = "hu_code")
    private String huCode;

    @Column(name = "ts", nullable = false)
    private Instant ts = Instant.now();

    /** Function point, e.g. 'slot:A01','conveyor','station-2'. */
    @Column(name = "point")
    private String point;

    /** REQUESTED|RETRIEVED|INDUCTED|ARRIVED|QUEUED|DONE|RECIRCULATE… */
    @Column(name = "event", nullable = false)
    private String event;

    @Column(name = "decision")
    private String decision;

    @Column(name = "from_point")
    private String fromPoint;

    @Column(name = "to_point")
    private String toPoint;

    @Column(name = "workplace_id")
    private UUID workplaceId;

    /** == hu_id today (mirrors device_task grouping). */
    @Column(name = "correlation_id")
    private UUID correlationId;

    /** The driving device_task, when applicable. */
    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "induction_entry_id")
    private UUID inductionEntryId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
    }

    public String getHuCode() {
        return huCode;
    }

    public void setHuCode(String huCode) {
        this.huCode = huCode;
    }

    public Instant getTs() {
        return ts;
    }

    public void setTs(Instant ts) {
        this.ts = ts;
    }

    public String getPoint() {
        return point;
    }

    public void setPoint(String point) {
        this.point = point;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getFromPoint() {
        return fromPoint;
    }

    public void setFromPoint(String fromPoint) {
        this.fromPoint = fromPoint;
    }

    public String getToPoint() {
        return toPoint;
    }

    public void setToPoint(String toPoint) {
        this.toPoint = toPoint;
    }

    public UUID getWorkplaceId() {
        return workplaceId;
    }

    public void setWorkplaceId(UUID workplaceId) {
        this.workplaceId = workplaceId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getInductionEntryId() {
        return inductionEntryId;
    }

    public void setInductionEntryId(UUID inductionEntryId) {
        this.inductionEntryId = inductionEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
