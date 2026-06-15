package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A cross-system physical move ({@code POST /api/flow/moves} where source and destination are served
 * by different storage systems / transport families). The HU cannot be carried by a single device,
 * so the move runs a multi-leg chain {@code RETRIEVE → CONVEY → STORE}, mirroring the induction
 * return-leg orchestration. This row tracks each leg's device-task id so the completing leg's
 * callback advances to the next leg, and the final STORE completion books the HU's destination
 * location in inventory + fires the {@code HandlingUnitMoved} audit.
 *
 * <p>Status {@code PENDING} (RETRIEVE in flight) → {@code RETRIEVED} (CONVEY in flight) →
 * {@code CONVEYED} (STORE in flight) → {@code STORED} (done); {@code FAILED} on any leg failure
 * (the chain stops, the row stays for inspection / recovery). The same-system case is a single
 * RELOCATE and persists NO chain row.
 */
@Entity
@Table(name = "flow_move_chain")
public class FlowMoveChain extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "move_chain_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id", nullable = false)
    private UUID huId;

    @Column(name = "hu_code")
    private String huCode;

    /** Source storage slot (RETRIEVE origin). */
    @Column(name = "from_location_id")
    private UUID fromLocationId;

    /** Destination slot the final STORE books the HU into. */
    @Column(name = "to_location_id", nullable = false)
    private UUID toLocationId;

    /** Equipment family of the source system (RETRIEVE leg). */
    @Column(name = "source_family", nullable = false)
    private String sourceFamily;

    /** Equipment family of the destination system (STORE leg). */
    @Column(name = "dest_family", nullable = false)
    private String destFamily;

    @Column(name = "reason")
    private String reason;

    /** PENDING | RETRIEVED | CONVEYED | STORED | FAILED. */
    @Column(name = "status", nullable = false)
    private String status = "PENDING";

    /** The RETRIEVE/BIN_RETRIEVE device_task driving the retrieval out of the source system. */
    @Column(name = "retrieve_task_id")
    private UUID retrieveTaskId;

    /** The CONVEY device_task carrying the HU from source discharge to destination entry. */
    @Column(name = "convey_task_id")
    private UUID conveyTaskId;

    /** The STORE/BIN_STORE device_task storing the HU into the destination location. */
    @Column(name = "store_task_id")
    private UUID storeTaskId;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
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

    public String getSourceFamily() {
        return sourceFamily;
    }

    public void setSourceFamily(String sourceFamily) {
        this.sourceFamily = sourceFamily;
    }

    public String getDestFamily() {
        return destFamily;
    }

    public void setDestFamily(String destFamily) {
        this.destFamily = destFamily;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public UUID getStoreTaskId() {
        return storeTaskId;
    }

    public void setStoreTaskId(UUID storeTaskId) {
        this.storeTaskId = storeTaskId;
    }
}
