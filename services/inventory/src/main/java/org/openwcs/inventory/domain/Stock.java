package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Durable authoritative current stock (build.md §4.2, §16). One row per physical
 * bucket: warehouse x SKU x batch/lot x location x handling-unit x status, with the
 * current level in {@code qty} (normalized to the SKU base UoM). Non-AVAILABLE
 * buckets are excluded from allocatable quantity reported to the host.
 *
 * <p>This table is the fast query surface; the transaction log remains the system
 * of record and can replay/rebuild it ({@code lastEventId} is the projection cursor).
 */
@Entity
@Table(name = "stock")
public class Stock extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "stock_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "hu_id")
    private UUID huId;

    /** AVAILABLE | LOCKED | QUARANTINE | DAMAGED. */
    @Column(name = "status", nullable = false)
    private String status = "AVAILABLE";

    @Column(name = "qty", nullable = false)
    private BigDecimal qty = BigDecimal.ZERO;

    @Column(name = "uom_code", nullable = false)
    private String uomCode = "EACH";

    /** Last transaction-log event applied to this row (idempotency / replay cursor). */
    @Column(name = "last_event_id")
    private UUID lastEventId;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getUomCode() {
        return uomCode;
    }

    public void setUomCode(String uomCode) {
        this.uomCode = uomCode;
    }

    public UUID getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(UUID lastEventId) {
        this.lastEventId = lastEventId;
    }
}
