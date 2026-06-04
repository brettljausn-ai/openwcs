package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An instance of master-data equipment placed on a warehouse level in the automation topology /
 * 3D editor. {@code equipmentId} references the master-data equipment by id (no FK); position,
 * rotation, tilt and the actual placed envelope (length/width/height in metres) describe where and
 * how big it sits. Length is typically set by scaling the master geometry.
 */
@Entity
@Table(name = "placed_equipment")
public class PlacedEquipment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "placed_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** The level this equipment sits on. */
    @Column(name = "level_id")
    private UUID levelId;

    /** The master-data equipment, by reference (no FK). */
    @Column(name = "equipment_id")
    private UUID equipmentId;

    /** Instance label / identifier. */
    @Column(name = "code")
    private String code;

    @Column(name = "pos_x_m")
    private BigDecimal posXM;

    @Column(name = "pos_y_m")
    private BigDecimal posYM;

    @Column(name = "pos_z_m")
    private BigDecimal posZM;

    /** Yaw in degrees. */
    @Column(name = "rotation_deg")
    private BigDecimal rotationDeg;

    /** Incline/decline in degrees (e.g. conveyors between levels). */
    @Column(name = "tilt_deg")
    private BigDecimal tiltDeg;

    @Column(name = "length_m")
    private BigDecimal lengthM;

    @Column(name = "width_m")
    private BigDecimal widthM;

    @Column(name = "height_m")
    private BigDecimal heightM;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    /** For conveyors: centreline waypoints [[x,z], …] in metres (corners / turns / loops). Null or
     *  fewer than 2 points → render as a single straight box of lengthM. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "path")
    private List<List<Double>> path;

    /** Whether the path closes back to its first point (a loop). */
    @Column(name = "closed", nullable = false)
    private boolean closed = false;

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

    public UUID getLevelId() {
        return levelId;
    }

    public void setLevelId(UUID levelId) {
        this.levelId = levelId;
    }

    public UUID getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(UUID equipmentId) {
        this.equipmentId = equipmentId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public BigDecimal getPosXM() {
        return posXM;
    }

    public void setPosXM(BigDecimal posXM) {
        this.posXM = posXM;
    }

    public BigDecimal getPosYM() {
        return posYM;
    }

    public void setPosYM(BigDecimal posYM) {
        this.posYM = posYM;
    }

    public BigDecimal getPosZM() {
        return posZM;
    }

    public void setPosZM(BigDecimal posZM) {
        this.posZM = posZM;
    }

    public BigDecimal getRotationDeg() {
        return rotationDeg;
    }

    public void setRotationDeg(BigDecimal rotationDeg) {
        this.rotationDeg = rotationDeg;
    }

    public BigDecimal getTiltDeg() {
        return tiltDeg;
    }

    public void setTiltDeg(BigDecimal tiltDeg) {
        this.tiltDeg = tiltDeg;
    }

    public BigDecimal getLengthM() {
        return lengthM;
    }

    public void setLengthM(BigDecimal lengthM) {
        this.lengthM = lengthM;
    }

    public BigDecimal getWidthM() {
        return widthM;
    }

    public void setWidthM(BigDecimal widthM) {
        this.widthM = widthM;
    }

    public BigDecimal getHeightM() {
        return heightM;
    }

    public void setHeightM(BigDecimal heightM) {
        this.heightM = heightM;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<List<Double>> getPath() {
        return path;
    }

    public void setPath(List<List<Double>> path) {
        this.path = path;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }
}
