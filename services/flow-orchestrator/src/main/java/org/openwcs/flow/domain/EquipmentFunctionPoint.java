package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A functional point on a placed piece of equipment — a scan, label applicator, divert, DWS,
 * query point, wrapper, induct or discharge location, positioned by {@code offsetM} along the
 * equipment from its origin (and optionally a side). {@code nodeCode} maps the point to a conveyor
 * routing node for the routing engine.
 */
@Entity
@Table(name = "equipment_function_point")
public class EquipmentFunctionPoint extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "point_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** The placed equipment this point is on. */
    @Column(name = "placed_id", nullable = false)
    private UUID placedId;

    /** SCAN / LABEL_APPLICATOR / DIVERT_LEFT / DIVERT_RIGHT / DWS / QUERY_POINT / WRAPPER / INDUCT / DISCHARGE. */
    @Column(name = "function_type")
    private String functionType;

    @Column(name = "name")
    private String name;

    /** Distance along the equipment from its origin, in metres. */
    @Column(name = "offset_m")
    private BigDecimal offsetM;

    /** LEFT / RIGHT, or null. */
    @Column(name = "side")
    private String side;

    /** The conveyor node code this point maps to for routing, or null. */
    @Column(name = "node_code")
    private String nodeCode;

    /** For a divert: the default direction when a tote has no route demanding otherwise —
     *  STRAIGHT (continue the main line) / BRANCH (take the divert's branch), or null (stop). */
    @Column(name = "default_exit")
    private String defaultExit;

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

    public UUID getPlacedId() {
        return placedId;
    }

    public void setPlacedId(UUID placedId) {
        this.placedId = placedId;
    }

    public String getFunctionType() {
        return functionType;
    }

    public void setFunctionType(String functionType) {
        this.functionType = functionType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getOffsetM() {
        return offsetM;
    }

    public void setOffsetM(BigDecimal offsetM) {
        this.offsetM = offsetM;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public String getNodeCode() {
        return nodeCode;
    }

    public void setNodeCode(String nodeCode) {
        this.nodeCode = nodeCode;
    }

    public String getDefaultExit() {
        return defaultExit;
    }

    public void setDefaultExit(String defaultExit) {
        this.defaultExit = defaultExit;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
