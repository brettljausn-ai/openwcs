package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A pick-and-put work cycle: a stock HU of one SKU presented at a STOCK node, against which the
 * station's open per-destination demand was matched to produce a put-list (see
 * {@link PutInstruction}). {@code remainingQty} tracks stock left in the HU as puts are confirmed.
 */
@Entity
@Table(name = "work_cycle")
public class WorkCycle extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "work_cycle_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "gtp_station_id", nullable = false)
    private UUID stationId;

    @Column(name = "stock_node_id", nullable = false)
    private UUID stockNodeId;

    @Column(name = "stock_hu_id", nullable = false)
    private UUID stockHuId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "presented_qty", nullable = false)
    private BigDecimal presentedQty;

    @Column(name = "remaining_qty", nullable = false)
    private BigDecimal remainingQty;

    /** OPEN | COMPLETED | CLOSED. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private Map<String, Object> details;

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

    public UUID getStockNodeId() {
        return stockNodeId;
    }

    public void setStockNodeId(UUID stockNodeId) {
        this.stockNodeId = stockNodeId;
    }

    public UUID getStockHuId() {
        return stockHuId;
    }

    public void setStockHuId(UUID stockHuId) {
        this.stockHuId = stockHuId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public BigDecimal getPresentedQty() {
        return presentedQty;
    }

    public void setPresentedQty(BigDecimal presentedQty) {
        this.presentedQty = presentedQty;
    }

    public BigDecimal getRemainingQty() {
        return remainingQty;
    }

    public void setRemainingQty(BigDecimal remainingQty) {
        this.remainingQty = remainingQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
