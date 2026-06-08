package org.openwcs.counting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A per-(location, SKU[, batch]) line of a {@link CountTask}: the {@code expectedQty} snapshot
 * taken from inventory at task generation, the operator-entered {@code countedQty}, and the
 * computed {@code variance} (counted − expected). On approval, the line drives a StockAdjusted
 * adjustment ({@code qtyDelta = variance}); {@code adjustmentEventId} records the posted event.
 */
@Entity
@Table(name = "count_line")
public class CountLine extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "count_line_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "count_task_id", nullable = false)
    private UUID countTaskId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "batch_id")
    private UUID batchId;

    /** Base-UoM label carried onto the adjustment payload. */
    @Column(name = "uom_code")
    private String uomCode;

    @Column(name = "expected_qty", nullable = false)
    private BigDecimal expectedQty = BigDecimal.ZERO;

    @Column(name = "counted_qty")
    private BigDecimal countedQty;

    @Column(name = "variance")
    private BigDecimal variance;

    /** PENDING | COUNTED | APPROVED | RECOUNT | ADJUSTED. */
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    /** Seam: the StockAdjusted txlog event id posted on approval. */
    @Column(name = "adjustment_event_id")
    private UUID adjustmentEventId;

    /** True once this cell's tote has been routed to a counting station (makes routing idempotent). */
    @Column(name = "routed", nullable = false)
    private boolean routed = false;

    /** The operator's previous at-station count, held to compare against the next one (blind recount). */
    @Column(name = "station_last_count")
    private BigDecimal stationLastCount;

    /** At-station blind count state: PENDING | RECOUNT | ACCEPTED | ADJUSTED. */
    @Column(name = "station_count_state", nullable = false)
    private String stationCountState = "PENDING";

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCountTaskId() {
        return countTaskId;
    }

    public void setCountTaskId(UUID countTaskId) {
        this.countTaskId = countTaskId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
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

    public String getUomCode() {
        return uomCode;
    }

    public void setUomCode(String uomCode) {
        this.uomCode = uomCode;
    }

    public BigDecimal getExpectedQty() {
        return expectedQty;
    }

    public void setExpectedQty(BigDecimal expectedQty) {
        this.expectedQty = expectedQty;
    }

    public BigDecimal getCountedQty() {
        return countedQty;
    }

    public void setCountedQty(BigDecimal countedQty) {
        this.countedQty = countedQty;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public void setVariance(BigDecimal variance) {
        this.variance = variance;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getAdjustmentEventId() {
        return adjustmentEventId;
    }

    public void setAdjustmentEventId(UUID adjustmentEventId) {
        this.adjustmentEventId = adjustmentEventId;
    }

    public boolean isRouted() {
        return routed;
    }

    public void setRouted(boolean routed) {
        this.routed = routed;
    }

    public BigDecimal getStationLastCount() {
        return stationLastCount;
    }

    public void setStationLastCount(BigDecimal stationLastCount) {
        this.stationLastCount = stationLastCount;
    }

    public String getStationCountState() {
        return stationCountState;
    }

    public void setStationCountState(String stationCountState) {
        this.stationCountState = stationCountState;
    }
}
