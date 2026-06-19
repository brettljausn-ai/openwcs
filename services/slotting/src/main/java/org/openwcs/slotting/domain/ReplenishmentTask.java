package org.openwcs.slotting.domain;

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
 * A generated replenishment move that tops a pick face back up toward its max. {@code priority}
 * is EMERGENCY (face empty / blocking demand), SCHEDULED, or OPPORTUNISTIC (off-peak top-off);
 * {@code triggerType} records whether it fired below-min or as a top-off.
 */
@Entity
@Table(name = "replenishment_task")
public class ReplenishmentTask extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "replenishment_task_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "uom_id", nullable = false)
    private UUID uomId;

    @Column(name = "from_location_id")
    private UUID fromLocationId;

    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    @Column(name = "qty", nullable = false)
    private BigDecimal qty;

    /** EMERGENCY | SCHEDULED | OPPORTUNISTIC. */
    @Column(name = "priority", nullable = false)
    private String priority = "SCHEDULED";

    /** BELOW_MIN | TOP_OFF. */
    @Column(name = "trigger_type", nullable = false)
    private String triggerType = "BELOW_MIN";

    /** PLANNED | DISPATCHED | DONE | CANCELLED. */
    @Column(name = "status", nullable = false)
    private String status = "PLANNED";

    /** The flow device-task created when this task was dispatched as a physical replenishment move. */
    @Column(name = "device_task_id")
    private UUID deviceTaskId;

    /** Operator (X-Auth-User) this task is reserved to via claim-next, or null if unreserved. */
    @Column(name = "reserved_by")
    private String reservedBy;

    /** When the reservation was taken (drives the stale-reservation sweeper), or null. */
    @Column(name = "reserved_at")
    private Instant reservedAt;

    /** The handheld/runtime instance that holds the reservation (so release can target it), or null. */
    @Column(name = "reserved_by_instance")
    private String reservedByInstance;

    /** When collection work began (set on dispatch): a started task is never released or swept. */
    @Column(name = "started_at")
    private Instant startedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public UUID getUomId() {
        return uomId;
    }

    public void setUomId(UUID uomId) {
        this.uomId = uomId;
    }

    public UUID getFromLocationId() {
        return fromLocationId;
    }

    public void setFromLocationId(UUID fromLocationId) {
        this.fromLocationId = fromLocationId;
    }

    public UUID getToLocationId() {
        return toLocationId;
    }

    public void setToLocationId(UUID toLocationId) {
        this.toLocationId = toLocationId;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getDeviceTaskId() {
        return deviceTaskId;
    }

    public void setDeviceTaskId(UUID deviceTaskId) {
        this.deviceTaskId = deviceTaskId;
    }

    public String getReservedBy() {
        return reservedBy;
    }

    public void setReservedBy(String reservedBy) {
        this.reservedBy = reservedBy;
    }

    public Instant getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(Instant reservedAt) {
        this.reservedAt = reservedAt;
    }

    public String getReservedByInstance() {
        return reservedByInstance;
    }

    public void setReservedByInstance(String reservedByInstance) {
        this.reservedByInstance = reservedByInstance;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }
}
