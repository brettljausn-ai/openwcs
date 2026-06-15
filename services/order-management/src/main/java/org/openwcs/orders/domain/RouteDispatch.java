package org.openwcs.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted dispatch wave: one row per (warehouseId, routeCode, cutoff) that outbound orders are
 * assigned to. Backs the dispatch board with real status + timestamps instead of deriving them on
 * the fly. Created/upserted when an order is assigned to a route, then advanced best-effort as its
 * orders progress: OPEN -> LOADING (firstLoadedAt set when the first assigned order is ready/picked)
 * -> DEPARTED (departedAt set when every assigned order has shipped).
 *
 * @see RouteDispatchStatus
 */
@Entity
@Table(name = "route_dispatch",
        uniqueConstraints = @UniqueConstraint(
                name = "route_dispatch_uk",
                columnNames = {"warehouse_id", "route_code", "cutoff"}))
public class RouteDispatch extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "dispatch_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "route_code", nullable = false)
    private String routeCode;

    /** The shared route cut-off instant (the assigned orders' dispatchBy); the board groups on it. */
    @Column(name = "cutoff", nullable = false)
    private Instant cutoff;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RouteDispatchStatus status = RouteDispatchStatus.OPEN;

    @Column(name = "orders_assigned", nullable = false)
    private int ordersAssigned;

    @Column(name = "orders_ready", nullable = false)
    private int ordersReady;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    /** When the first assigned order became ready/picked (LOADING); null while OPEN. */
    @Column(name = "first_loaded_at")
    private Instant firstLoadedAt;

    /** Actual departure: when every assigned order had shipped (DEPARTED); null until then. */
    @Column(name = "departed_at")
    private Instant departedAt;

    protected RouteDispatch() {
    }

    public RouteDispatch(UUID warehouseId, String routeCode, Instant cutoff) {
        this.warehouseId = warehouseId;
        this.routeCode = routeCode;
        this.cutoff = cutoff;
        this.status = RouteDispatchStatus.OPEN;
        this.openedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public String getRouteCode() {
        return routeCode;
    }

    public Instant getCutoff() {
        return cutoff;
    }

    public RouteDispatchStatus getStatus() {
        return status;
    }

    public void setStatus(RouteDispatchStatus status) {
        this.status = status;
    }

    public int getOrdersAssigned() {
        return ordersAssigned;
    }

    public void setOrdersAssigned(int ordersAssigned) {
        this.ordersAssigned = ordersAssigned;
    }

    public int getOrdersReady() {
        return ordersReady;
    }

    public void setOrdersReady(int ordersReady) {
        this.ordersReady = ordersReady;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getFirstLoadedAt() {
        return firstLoadedAt;
    }

    public void setFirstLoadedAt(Instant firstLoadedAt) {
        this.firstLoadedAt = firstLoadedAt;
    }

    public Instant getDepartedAt() {
        return departedAt;
    }

    public void setDepartedAt(Instant departedAt) {
        this.departedAt = departedAt;
    }
}
