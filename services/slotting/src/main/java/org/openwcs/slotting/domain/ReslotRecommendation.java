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
 * An off-peak re-slot recommendation: move a stored HU from one location to a better one as the
 * SKU's velocity drifts (or to consolidate a partial lane / rebalance a hot aisle).
 */
@Entity
@Table(name = "reslot_recommendation")
public class ReslotRecommendation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reslot_recommendation_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "from_location_id", nullable = false)
    private UUID fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "score_gain")
    private BigDecimal scoreGain;

    /** RECOMMENDED | DISPATCHED | DONE | DISMISSED. */
    @Column(name = "status", nullable = false)
    private String status = "RECOMMENDED";

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

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public UUID getFromLocationId() {
        return fromLocationId;
    }

    public void setFromLocationId(UUID fromLocationId) {
        this.fromLocationId = fromLocationId;
    }

    public UUID getToLocationId() {
        return toLocationId;
    }

    public void setToLocationId(UUID toLocationId) {
        this.toLocationId = toLocationId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public BigDecimal getScoreGain() {
        return scoreGain;
    }

    public void setScoreGain(BigDecimal scoreGain) {
        this.scoreGain = scoreGain;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
