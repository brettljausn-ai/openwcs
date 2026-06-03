package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Open demand pinned to an ORDER node: how much of a SKU still needs to be put there for a
 * given order/line. The station clears this from presented stock HUs — one stock HU of a SKU
 * typically serves many of these across the station (the goods-to-person batch).
 */
@Entity
@Table(name = "destination_demand")
public class DestinationDemand extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "destination_demand_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "station_node_id", nullable = false)
    private UUID stationNodeId;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "requested_qty", nullable = false)
    private BigDecimal requestedQty;

    @Column(name = "putted_qty", nullable = false)
    private BigDecimal puttedQty = BigDecimal.ZERO;

    /** OPEN | COMPLETED | CANCELLED. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    /** Units still needed at this destination. */
    public BigDecimal remaining() {
        return requestedQty.subtract(puttedQty);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStationNodeId() {
        return stationNodeId;
    }

    public void setStationNodeId(UUID stationNodeId) {
        this.stationNodeId = stationNodeId;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public UUID getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(UUID orderLineId) {
        this.orderLineId = orderLineId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public BigDecimal getRequestedQty() {
        return requestedQty;
    }

    public void setRequestedQty(BigDecimal requestedQty) {
        this.requestedQty = requestedQty;
    }

    public BigDecimal getPuttedQty() {
        return puttedQty;
    }

    public void setPuttedQty(BigDecimal puttedQty) {
        this.puttedQty = puttedQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
