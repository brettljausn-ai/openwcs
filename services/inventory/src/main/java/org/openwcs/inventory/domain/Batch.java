package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Batch / lot — instance data captured at goods-in (build.md §6), NOT SKU master
 * data. Owned by the inventory service. {@code skuId} references master_data.sku
 * across the service boundary (no DB-level FK; build.md §5.3).
 */
@Entity
@Table(name = "batch")
public class Batch extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "batch_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "batch_number", nullable = false)
    private String batchNumber;

    @Column(name = "supplier_lot")
    private String supplierLot;

    @Column(name = "production_date")
    private LocalDate productionDate;

    @Column(name = "best_before_date")
    private LocalDate bestBeforeDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "country_of_origin")
    private String countryOfOrigin;

    /** RELEASED | QUARANTINE | BLOCKED. */
    @Column(name = "quality_status", nullable = false)
    private String qualityStatus = "RELEASED";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false)
    private Map<String, Object> attributes = new HashMap<>();

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

    public String getBatchNumber() {
        return batchNumber;
    }

    public void setBatchNumber(String batchNumber) {
        this.batchNumber = batchNumber;
    }

    public String getSupplierLot() {
        return supplierLot;
    }

    public void setSupplierLot(String supplierLot) {
        this.supplierLot = supplierLot;
    }

    public LocalDate getProductionDate() {
        return productionDate;
    }

    public void setProductionDate(LocalDate productionDate) {
        this.productionDate = productionDate;
    }

    public LocalDate getBestBeforeDate() {
        return bestBeforeDate;
    }

    public void setBestBeforeDate(LocalDate bestBeforeDate) {
        this.bestBeforeDate = bestBeforeDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(LocalDate expiryDate) {
        this.expiryDate = expiryDate;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public void setCountryOfOrigin(String countryOfOrigin) {
        this.countryOfOrigin = countryOfOrigin;
    }

    public String getQualityStatus() {
        return qualityStatus;
    }

    public void setQualityStatus(String qualityStatus) {
        this.qualityStatus = qualityStatus;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
