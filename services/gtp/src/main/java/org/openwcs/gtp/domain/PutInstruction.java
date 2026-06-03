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
 * One line of a cycle's put-list: put {@code qty} of the cycle's SKU into a destination node,
 * lighting its put-light. Confirming decrements the cycle's remaining stock and the destination
 * demand's putted qty; a short confirm closes the instruction with less than {@code qty}.
 */
@Entity
@Table(name = "put_instruction")
public class PutInstruction extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "put_instruction_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "work_cycle_id", nullable = false)
    private UUID workCycleId;

    @Column(name = "destination_node_id", nullable = false)
    private UUID destinationNodeId;

    @Column(name = "destination_demand_id", nullable = false)
    private UUID destinationDemandId;

    @Column(name = "order_ref", nullable = false)
    private String orderRef;

    @Column(name = "order_line_id")
    private UUID orderLineId;

    @Column(name = "order_hu_id")
    private UUID orderHuId;

    @Column(name = "put_light_id")
    private String putLightId;

    @Column(name = "qty", nullable = false)
    private BigDecimal qty;

    @Column(name = "confirmed_qty", nullable = false)
    private BigDecimal confirmedQty = BigDecimal.ZERO;

    /** OPEN | CONFIRMED | SHORT | CANCELLED. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkCycleId() {
        return workCycleId;
    }

    public void setWorkCycleId(UUID workCycleId) {
        this.workCycleId = workCycleId;
    }

    public UUID getDestinationNodeId() {
        return destinationNodeId;
    }

    public void setDestinationNodeId(UUID destinationNodeId) {
        this.destinationNodeId = destinationNodeId;
    }

    public UUID getDestinationDemandId() {
        return destinationDemandId;
    }

    public void setDestinationDemandId(UUID destinationDemandId) {
        this.destinationDemandId = destinationDemandId;
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

    public UUID getOrderHuId() {
        return orderHuId;
    }

    public void setOrderHuId(UUID orderHuId) {
        this.orderHuId = orderHuId;
    }

    public String getPutLightId() {
        return putLightId;
    }

    public void setPutLightId(String putLightId) {
        this.putLightId = putLightId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public BigDecimal getConfirmedQty() {
        return confirmedQty;
    }

    public void setConfirmedQty(BigDecimal confirmedQty) {
        this.confirmedQty = confirmedQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
