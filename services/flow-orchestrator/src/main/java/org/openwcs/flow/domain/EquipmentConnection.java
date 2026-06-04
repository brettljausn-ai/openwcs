package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A directed connection between two placed pieces of equipment in the automation topology,
 * optionally anchored at specific function points (e.g. a discharge point feeding an induct
 * point). Describes how handling units flow from one piece of equipment to the next.
 */
@Entity
@Table(name = "equipment_connection")
public class EquipmentConnection extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "connection_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "from_placed_id", nullable = false)
    private UUID fromPlacedId;

    @Column(name = "to_placed_id", nullable = false)
    private UUID toPlacedId;

    /** Optional source function point. */
    @Column(name = "from_point_id")
    private UUID fromPointId;

    /** Optional target function point. */
    @Column(name = "to_point_id")
    private UUID toPointId;

    @Column(name = "label")
    private String label;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

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

    public UUID getFromPlacedId() {
        return fromPlacedId;
    }

    public void setFromPlacedId(UUID fromPlacedId) {
        this.fromPlacedId = fromPlacedId;
    }

    public UUID getToPlacedId() {
        return toPlacedId;
    }

    public void setToPlacedId(UUID toPlacedId) {
        this.toPlacedId = toPlacedId;
    }

    public UUID getFromPointId() {
        return fromPointId;
    }

    public void setFromPointId(UUID fromPointId) {
        this.fromPointId = fromPointId;
    }

    public UUID getToPointId() {
        return toPointId;
    }

    public void setToPointId(UUID toPointId) {
        this.toPointId = toPointId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
