package org.openwcs.orders.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Outbound fulfilment order — aggregate root over its {@link OrderLine}s (build.md §4.6). */
@Entity
@Table(name = "outbound_order")
public class OutboundOrder extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "order_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "order_ref", nullable = false, unique = true)
    private String orderRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType = OrderType.OUTBOUND;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "customer_ref")
    private String customerRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.CREATED;

    /** Why the order is in its current status (e.g. the SKU/line that could not be cubed). */
    @Column(name = "status_detail")
    private String statusDetail;

    @Column(name = "priority", nullable = false)
    private int priority;

    /** Required ship / cut-off time; drives release ordering with priority. */
    @Column(name = "dispatch_by")
    private Instant dispatchBy;

    /** Dispatch service level (master-data shipping-service code), e.g. EXPRESS. */
    @Column(name = "service_code")
    private String serviceCode;

    /** Dispatch route (master-data route code, host-fed), e.g. CENTRAL_LONDON. */
    @Column(name = "route_code")
    private String routeCode;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLine> lines = new ArrayList<>();

    public void addLine(OrderLine line) {
        line.setOrder(this);
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

    public OrderType getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderType orderType) {
        this.orderType = orderType;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public void setCustomerRef(String customerRef) {
        this.customerRef = customerRef;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getStatusDetail() {
        return statusDetail;
    }

    public void setStatusDetail(String statusDetail) {
        this.statusDetail = statusDetail;
    }

    public String getServiceCode() {
        return serviceCode;
    }

    public void setServiceCode(String serviceCode) {
        this.serviceCode = serviceCode;
    }

    public String getRouteCode() {
        return routeCode;
    }

    public void setRouteCode(String routeCode) {
        this.routeCode = routeCode;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public Instant getDispatchBy() {
        return dispatchBy;
    }

    public void setDispatchBy(Instant dispatchBy) {
        this.dispatchBy = dispatchBy;
    }

    public List<OrderLine> getLines() {
        return lines;
    }
}
