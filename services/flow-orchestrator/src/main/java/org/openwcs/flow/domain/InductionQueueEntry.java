package org.openwcs.flow.domain;

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
 * The inbound induction / presentation queue entry for a tote being delivered to a workplace
 * (ADR-0007 §2.1). Lifecycle {@code REQUESTED → IN_TRANSIT → QUEUED → DONE}: a request creates a
 * {@code REQUESTED} entry; the RETRIEVE device-task callback advances it to {@code IN_TRANSIT}; the
 * CONVEY (= arrival) callback advances it to {@code QUEUED} and assigns its arrival sequence; the
 * operator completes it to {@code DONE}. The per-station cap counts only {@code {IN_TRANSIT,QUEUED}}.
 */
@Entity
@Table(name = "induction_queue_entry")
public class InductionQueueEntry extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "induction_entry_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** Destination workplace (today: a GTP station id). */
    @Column(name = "workplace_id", nullable = false)
    private UUID workplaceId;

    /** GTP_STATION | PUT_WALL | … (R1). */
    @Column(name = "workplace_kind", nullable = false)
    private String workplaceKind = "GTP_STATION";

    @Column(name = "hu_id")
    private UUID huId;

    @Column(name = "hu_code")
    private String huCode;

    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "sku_code")
    private String skuCode;

    @Column(name = "qty")
    private BigDecimal qty;

    /** Source storage slot (for store-back by gtp). */
    @Column(name = "location_id")
    private UUID locationId;

    /** PICKING | STOCK_COUNT | … */
    @Column(name = "mode", nullable = false)
    private String mode;

    /** REQUESTED | IN_TRANSIT | QUEUED | DONE. */
    @Column(name = "status", nullable = false)
    private String status = "REQUESTED";

    /** Arrival order, assigned at QUEUED time. NULL until QUEUED. */
    @Column(name = "arrival_seq")
    private Long arrivalSeq;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "in_transit_at")
    private Instant inTransitAt;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "done_at")
    private Instant doneAt;

    /** The RETRIEVE/BIN_RETRIEVE device_task driving the retrieval. */
    @Column(name = "retrieve_task_id")
    private UUID retrieveTaskId;

    /** The CONVEY device_task driving the conveyor leg. */
    @Column(name = "convey_task_id")
    private UUID conveyTaskId;

    @Column(name = "count_task_id")
    private UUID countTaskId;

    @Column(name = "count_line_id")
    private UUID countLineId;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getWorkplaceId() {
        return workplaceId;
    }

    public void setWorkplaceId(UUID workplaceId) {
        this.workplaceId = workplaceId;
    }

    public String getWorkplaceKind() {
        return workplaceKind;
    }

    public void setWorkplaceKind(String workplaceKind) {
        this.workplaceKind = workplaceKind;
    }

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
    }

    public String getHuCode() {
        return huCode;
    }

    public void setHuCode(String huCode) {
        this.huCode = huCode;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public BigDecimal getQty() {
        return qty;
    }

    public void setQty(BigDecimal qty) {
        this.qty = qty;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getArrivalSeq() {
        return arrivalSeq;
    }

    public void setArrivalSeq(Long arrivalSeq) {
        this.arrivalSeq = arrivalSeq;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getInTransitAt() {
        return inTransitAt;
    }

    public void setInTransitAt(Instant inTransitAt) {
        this.inTransitAt = inTransitAt;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public void setQueuedAt(Instant queuedAt) {
        this.queuedAt = queuedAt;
    }

    public Instant getDoneAt() {
        return doneAt;
    }

    public void setDoneAt(Instant doneAt) {
        this.doneAt = doneAt;
    }

    public UUID getRetrieveTaskId() {
        return retrieveTaskId;
    }

    public void setRetrieveTaskId(UUID retrieveTaskId) {
        this.retrieveTaskId = retrieveTaskId;
    }

    public UUID getConveyTaskId() {
        return conveyTaskId;
    }

    public void setConveyTaskId(UUID conveyTaskId) {
        this.conveyTaskId = conveyTaskId;
    }

    public UUID getCountTaskId() {
        return countTaskId;
    }

    public void setCountTaskId(UUID countTaskId) {
        this.countTaskId = countTaskId;
    }

    public UUID getCountLineId() {
        return countLineId;
    }

    public void setCountLineId(UUID countLineId) {
        this.countLineId = countLineId;
    }
}
