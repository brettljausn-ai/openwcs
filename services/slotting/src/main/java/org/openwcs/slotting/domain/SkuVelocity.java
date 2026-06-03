package org.openwcs.slotting.domain;

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
 * Recency-weighted pick-frequency score for one (warehouse, SKU). The {@link #score} is an
 * exponentially weighted moving average (EWMA): each recompute first decays it toward zero
 * by {@code exp(-Δt / tau)} (so recent picks dominate and stale activity fades), then folds
 * in the {@link #pendingPicks} accumulated by the transaction-log consumer since the last
 * decay. The classifier ranks SKUs by {@link #score} and records the assigned A/B/C label in
 * {@link #velocityClass} (derived; {@code storage_profile.velocity_class} stays authoritative
 * for the put-away engine).
 */
@Entity
@Table(name = "sku_velocity")
public class SkuVelocity extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "sku_velocity_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "score", nullable = false)
    private BigDecimal score = BigDecimal.ZERO;

    @Column(name = "pending_picks", nullable = false)
    private BigDecimal pendingPicks = BigDecimal.ZERO;

    @Column(name = "velocity_class")
    private String velocityClass;

    @Column(name = "decayed_at")
    private Instant decayedAt;

    @Column(name = "last_pick_at")
    private Instant lastPickAt;

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

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public BigDecimal getPendingPicks() {
        return pendingPicks;
    }

    public void setPendingPicks(BigDecimal pendingPicks) {
        this.pendingPicks = pendingPicks;
    }

    public String getVelocityClass() {
        return velocityClass;
    }

    public void setVelocityClass(String velocityClass) {
        this.velocityClass = velocityClass;
    }

    public Instant getDecayedAt() {
        return decayedAt;
    }

    public void setDecayedAt(Instant decayedAt) {
        this.decayedAt = decayedAt;
    }

    public Instant getLastPickAt() {
        return lastPickAt;
    }

    public void setLastPickAt(Instant lastPickAt) {
        this.lastPickAt = lastPickAt;
    }
}
