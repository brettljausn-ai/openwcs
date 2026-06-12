package org.openwcs.allocation.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The allocation + cube plan for one order (ADR 0002). Aggregate root over its lines. */
@Entity
@Table(name = "order_allocation")
public class OrderAllocation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "allocation_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_ref", nullable = false, unique = true)
    private String orderRef;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** FULFILLABLE | FULFILLABLE_SHORT | NOT_FULFILLABLE | CANCELLED | CUBING_FAILED. */
    @Column(name = "status", nullable = false)
    private String status;

    /** Why the order is in its current status (e.g. the SKU/line that could not be cubed). */
    @Column(name = "status_detail")
    private String statusDetail;

    /** APP | ONE_TO_ONE. */
    @Column(name = "cubing_mode", nullable = false)
    private String cubingMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shippers", nullable = false)
    private List<ShipperAssignment> shippers = new ArrayList<>();

    @OneToMany(mappedBy = "allocation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AllocationLine> lines = new ArrayList<>();

    public void addLine(AllocationLine line) {
        line.setAllocation(this);
        this.lines.add(line);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusDetail() {
        return statusDetail;
    }

    public void setStatusDetail(String statusDetail) {
        this.statusDetail = statusDetail;
    }

    public String getCubingMode() {
        return cubingMode;
    }

    public void setCubingMode(String cubingMode) {
        this.cubingMode = cubingMode;
    }

    public List<ShipperAssignment> getShippers() {
        return shippers;
    }

    public void setShippers(List<ShipperAssignment> shippers) {
        this.shippers = shippers;
    }

    public List<AllocationLine> getLines() {
        return lines;
    }
}
