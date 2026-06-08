package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A handling unit pulled out of circulation for maintenance (the dirty-tote operator exception). The
 * tote does not store back to inventory: it goes to a cleaning/maintenance flow instead. Opened OPEN
 * with reason CLEANING when an operator flags a tote dirty at a station.
 */
@Entity
@Table(name = "maintenance_order")
public class MaintenanceOrder extends Auditable {

    /** Why the tote was pulled. */
    public enum Reason { CLEANING }

    /** Lifecycle of a maintenance order. */
    public enum Status { OPEN, CLOSED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "maintenance_order_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "hu_code")
    private String huCode;

    @Column(name = "gtp_station_id")
    private UUID stationId;

    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "reason", nullable = false)
    private String reason = Reason.CLEANING.name();

    @Column(name = "status", nullable = false)
    private String status = Status.OPEN.name();

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

    public String getHuCode() {
        return huCode;
    }

    public void setHuCode(String huCode) {
        this.huCode = huCode;
    }

    public UUID getStationId() {
        return stationId;
    }

    public void setStationId(UUID stationId) {
        this.stationId = stationId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
