package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One physical piece of a serial-tracked SKU (build.md §6). Created at goods-in
 * only when the SKU's {@code is_serial_tracked} flag is set; gives full per-piece
 * genealogy through the transaction log.
 */
@Entity
@Table(name = "serial_unit")
public class SerialUnit extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "serial_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "serial_number", nullable = false)
    private String serialNumber;

    /** IN_STOCK | ALLOCATED | PICKED | SHIPPED | BLOCKED. */
    @Column(name = "status", nullable = false)
    private String status = "IN_STOCK";

    @Column(name = "current_location_id")
    private UUID currentLocationId;

    @Column(name = "current_hu_id")
    private UUID currentHuId;

    @Column(name = "received_at")
    private Instant receivedAt;

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

    public UUID getBatchId() {
        return batchId;
    }

    public void setBatchId(UUID batchId) {
        this.batchId = batchId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getCurrentLocationId() {
        return currentLocationId;
    }

    public void setCurrentLocationId(UUID currentLocationId) {
        this.currentLocationId = currentLocationId;
    }

    public UUID getCurrentHuId() {
        return currentHuId;
    }

    public void setCurrentHuId(UUID currentHuId) {
        this.currentHuId = currentHuId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
    }
}
