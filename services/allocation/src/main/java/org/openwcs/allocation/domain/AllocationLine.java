package org.openwcs.allocation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Allocation result for one order line, with the picks that fulfil it. */
@Entity
@Table(name = "allocation_line")
public class AllocationLine extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "line_alloc_id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "allocation_id", nullable = false)
    private OrderAllocation allocation;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "requested_qty", nullable = false)
    private BigDecimal requestedQty;

    @Column(name = "allocated_qty", nullable = false)
    private BigDecimal allocatedQty = BigDecimal.ZERO;

    /** ALLOCATED | SHORT. */
    @Column(name = "status", nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "picks", nullable = false)
    private List<Pick> picks = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public OrderAllocation getAllocation() {
        return allocation;
    }

    public void setAllocation(OrderAllocation allocation) {
        this.allocation = allocation;
    }

    public int getLineNo() {
        return lineNo;
    }

    public void setLineNo(int lineNo) {
        this.lineNo = lineNo;
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

    public BigDecimal getAllocatedQty() {
        return allocatedQty;
    }

    public void setAllocatedQty(BigDecimal allocatedQty) {
        this.allocatedQty = allocatedQty;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Pick> getPicks() {
        return picks;
    }

    public void setPicks(List<Pick> picks) {
        this.picks = picks;
    }
}
