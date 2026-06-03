package org.openwcs.slotting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-SKU teach-in slotting rule: the {@code block} a SKU is slotted into, plus its velocity
 * class and redundancy knobs. For automated blocks the put-away engine uses this to pick an
 * actual location; one profile per (warehouse, sku, block).
 */
@Entity
@Table(name = "storage_profile")
public class StorageProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "storage_profile_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    /** A | B | C velocity class (teach-in, or learned by the auto-ABC classifier). */
    @Column(name = "velocity_class", nullable = false)
    private String velocityClass = "B";

    /**
     * When true the auto-ABC classifier leaves {@link #velocityClass} alone — an operator has
     * pinned the class. Default false so the recency-weighted learner applies everywhere.
     */
    @Column(name = "manual_override", nullable = false)
    private boolean manualOverride = false;

    @Column(name = "consolidate", nullable = false)
    private boolean consolidate = true;

    @Column(name = "min_aisles", nullable = false)
    private int minAisles = 1;

    @Column(name = "max_aisle_pct", nullable = false)
    private BigDecimal maxAislePct = BigDecimal.ONE;

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

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public UUID getBlockId() {
        return blockId;
    }

    public void setBlockId(UUID blockId) {
        this.blockId = blockId;
    }

    public String getVelocityClass() {
        return velocityClass;
    }

    public void setVelocityClass(String velocityClass) {
        this.velocityClass = velocityClass;
    }

    public boolean isManualOverride() {
        return manualOverride;
    }

    public void setManualOverride(boolean manualOverride) {
        this.manualOverride = manualOverride;
    }

    public boolean isConsolidate() {
        return consolidate;
    }

    public void setConsolidate(boolean consolidate) {
        this.consolidate = consolidate;
    }

    public int getMinAisles() {
        return minAisles;
    }

    public void setMinAisles(int minAisles) {
        this.minAisles = minAisles;
    }

    public BigDecimal getMaxAislePct() {
        return maxAislePct;
    }

    public void setMaxAislePct(BigDecimal maxAislePct) {
        this.maxAislePct = maxAislePct;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
