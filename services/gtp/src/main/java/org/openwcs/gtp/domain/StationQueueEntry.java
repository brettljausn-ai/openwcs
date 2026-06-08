package org.openwcs.gtp.domain;

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
 * A handling unit routed to a workplace's inbound queue. While the emulator simulates the move it
 * is {@code IN_TRANSIT} with a computed {@code arrivalAt}; once due it becomes {@code QUEUED} and
 * the operator works the queue in arrival order; completing it marks it {@code DONE}.
 */
@Entity
@Table(name = "station_queue_entry")
public class StationQueueEntry extends Auditable {

    /** Lifecycle of a queued HU. */
    public enum Status { IN_TRANSIT, QUEUED, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "station_queue_entry_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "gtp_station_id", nullable = false)
    private UUID stationId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "hu_code")
    private String huCode;

    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "qty")
    private BigDecimal qty;

    /** The operating mode this HU is routed for (PICKING / STOCK_COUNT / ...). */
    @Column(name = "mode", nullable = false)
    private String mode;

    @Column(name = "status", nullable = false)
    private String status = Status.IN_TRANSIT.name();

    /** When the (simulated) transport delivers the HU to the station. */
    @Column(name = "arrival_at", nullable = false)
    private Instant arrivalAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStationId() {
        return stationId;
    }

    public void setStationId(UUID stationId) {
        this.stationId = stationId;
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

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getArrivalAt() {
        return arrivalAt;
    }

    public void setArrivalAt(Instant arrivalAt) {
        this.arrivalAt = arrivalAt;
    }
}
