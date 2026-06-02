package org.openwcs.allocation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A pick batch: several eligible orders grouped into one pick tote, picked as one
 * combined list, then separated into each order's final shippers at packing (ADR 0002 §6).
 */
@Entity
@Table(name = "pick_batch")
public class PickBatch extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "batch_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "pick_tote_shipper_id")
    private UUID pickToteShipperId;

    /** OPEN | PICKED | SEPARATED | CLOSED. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "members", nullable = false)
    private List<BatchMember> members = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pick_lines", nullable = false)
    private List<MergedPickLine> pickLines = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getPickToteShipperId() {
        return pickToteShipperId;
    }

    public void setPickToteShipperId(UUID pickToteShipperId) {
        this.pickToteShipperId = pickToteShipperId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<BatchMember> getMembers() {
        return members;
    }

    public void setMembers(List<BatchMember> members) {
        this.members = members;
    }

    public List<MergedPickLine> getPickLines() {
        return pickLines;
    }

    public void setPickLines(List<MergedPickLine> pickLines) {
        this.pickLines = pickLines;
    }
}
