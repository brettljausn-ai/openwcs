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
 * ABC-cadence config: how often a scope (a zone/block, a specific location/SKU, or a velocity
 * class) should be counted. The schedule generator emits a {@link CountTask} when {@code nextDueAt}
 * has passed, then advances {@code nextDueAt} by {@code cadenceDays} (A SKUs short, C SKUs long).
 */
@Entity
@Table(name = "count_schedule")
public class CountSchedule extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "count_schedule_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "name", nullable = false)
    private String name;

    /** LOCATION | SKU | ZONE | BLOCK | ABC_CLASS. */
    @Column(name = "scope_type", nullable = false)
    private String scopeType;

    /** The location/sku/zone/block id (null for ABC_CLASS). */
    @Column(name = "scope_ref")
    private UUID scopeRef;

    /** A | B | C when scopeType = ABC_CLASS. */
    @Column(name = "abc_class")
    private String abcClass;

    /** BLIND | VARIANCE — the count type emitted tasks inherit. */
    @Column(name = "count_type", nullable = false)
    private String countType = "BLIND";

    @Column(name = "cadence_days", nullable = false)
    private int cadenceDays;

    @Column(name = "tolerance", nullable = false)
    private BigDecimal tolerance = BigDecimal.ZERO;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt = Instant.now();

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getAbcClass() {
        return abcClass;
    }

    public void setAbcClass(String abcClass) {
        this.abcClass = abcClass;
    }

    public String getCountType() {
        return countType;
    }

    public void setCountType(String countType) {
        this.countType = countType;
    }

    public int getCadenceDays() {
        return cadenceDays;
    }

    public void setCadenceDays(int cadenceDays) {
        this.cadenceDays = cadenceDays;
    }

    public BigDecimal getTolerance() {
        return tolerance;
    }

    public void setTolerance(BigDecimal tolerance) {
        this.tolerance = tolerance;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Instant getNextDueAt() {
        return nextDueAt;
    }

    public void setNextDueAt(Instant nextDueAt) {
        this.nextDueAt = nextDueAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
