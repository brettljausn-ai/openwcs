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
 * A station work cycle, running one {@link OperatingMode}. For PICKING (the default) it is the
 * classic pick-and-put cycle: a stock HU of one SKU presented at a STOCK node, against which the
 * station's open per-destination demand was matched to produce a put-list (see
 * {@link PutInstruction}); {@code remainingQty} tracks stock left in the HU as puts are confirmed.
 *
 * <p>For the other operating modes the cycle carries a set of mode-appropriate {@link TaskLine}s
 * instead of put instructions (decant-moves, count entries, QC verdicts, maintenance checks), and
 * the PICKING-specific {@code stockHuId}/{@code skuId}/{@code presentedQty}/{@code remainingQty}
 * may be absent. DECANTING additionally presents an empty {@code targetHuId}.
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

    /** PICKING | DECANTING | STOCK_COUNT | QC | MAINTENANCE. */
    @Column(name = "operating_mode", nullable = false)
    private String operatingMode = OperatingMode.PICKING.name();

    @Column(name = "stock_node_id", nullable = false)
    private UUID stockNodeId;

    /** The presented stock/source HU. Always set for PICKING/DECANTING/STOCK_COUNT/QC. */
    @Column(name = "stock_hu_id")
    private UUID stockHuId;

    /** Empty target HU being filled (DECANTING only). */
    @Column(name = "target_hu_id")
    private UUID targetHuId;

    /** The single SKU of a PICKING cycle; null when the mode spans SKUs per task line. */
    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "presented_qty")
    private BigDecimal presentedQty;

    @Column(name = "remaining_qty")
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

    public String getOperatingMode() {
        return operatingMode;
    }

    public void setOperatingMode(String operatingMode) {
        this.operatingMode = operatingMode;
    }

    public UUID getTargetHuId() {
        return targetHuId;
    }

    public void setTargetHuId(UUID targetHuId) {
        this.targetHuId = targetHuId;
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
