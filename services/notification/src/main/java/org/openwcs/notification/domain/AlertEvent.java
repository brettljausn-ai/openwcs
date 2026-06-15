package org.openwcs.notification.domain;

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
 * One row per (warehouse, area, metric) breach lifecycle. The evaluator keeps at most one ACTIVE
 * (OPEN or ACKED) row per {@link #dedupeKey} so a metric that stays over threshold across runs
 * updates the same row instead of duplicating; when the metric drops back under threshold the row
 * is CLEARED. CLEARED rows remain as history.
 */
@Entity
@Table(name = "alert_event")
public class AlertEvent extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "alert_event_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "area", nullable = false)
    private String area;

    @Column(name = "metric", nullable = false)
    private String metric;

    /** WARNING | CRITICAL. */
    @Column(name = "severity", nullable = false)
    private String severity = "WARNING";

    @Column(name = "value")
    private BigDecimal value;

    @Column(name = "threshold")
    private BigDecimal threshold;

    /** OPEN | ACKED | CLEARED. */
    @Column(name = "state", nullable = false)
    private String state = "OPEN";

    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "acked_by")
    private String ackedBy;

    /** True while the alert should be shown to operators (OPEN or ACKED). */
    public boolean isActive() {
        return "OPEN".equals(state) || "ACKED".equals(state);
    }

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

    public String getArea() {
        return area;
    }

    public void setArea(String area) {
        this.area = area;
    }

    public String getMetric() {
        return metric;
    }

    public void setMetric(String metric) {
        this.metric = metric;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getThreshold() {
        return threshold;
    }

    public void setThreshold(BigDecimal threshold) {
        this.threshold = threshold;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public void setOpenedAt(Instant openedAt) {
        this.openedAt = openedAt;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(Instant clearedAt) {
        this.clearedAt = clearedAt;
    }

    public Instant getAckedAt() {
        return ackedAt;
    }

    public void setAckedAt(Instant ackedAt) {
        this.ackedAt = ackedAt;
    }

    public String getAckedBy() {
        return ackedBy;
    }

    public void setAckedBy(String ackedBy) {
        this.ackedBy = ackedBy;
    }
}
