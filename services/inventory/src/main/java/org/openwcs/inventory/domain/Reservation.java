package org.openwcs.inventory.domain;

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
 * Soft allocation of available stock to an outbound order / wave (build.md §7).
 * Available-to-promise = SUM(AVAILABLE stock qty) − SUM(HELD reservation qty).
 */
@Entity
@Table(name = "reservation")
public class Reservation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "reservation_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "order_ref")
    private String orderRef;

    /** Owning process instance (build.md §5.5 correlation id). */
    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "qty", nullable = false)
    private BigDecimal qty;

    /** HELD | CONSUMED | RELEASED | EXPIRED. */
    @Column(name = "status", nullable = false)
    private String status = "HELD";

    @Column(name = "expires_at")
    private Instant expiresAt;

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

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(UUID correlationId) {
        this.correlationId = correlationId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
