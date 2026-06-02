package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A dispatch container (box, tote, bag, …) configurable per warehouse, used for order
 * cubing (ADR 0002). Capacity is bounded by usable volume ({@code maxFillLevel} of the
 * inner volume) and {@code maxWeightG} (gross, including {@code tareWeightG}).
 */
@Entity
@Table(name = "shipper")
public class Shipper extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "shipper_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    /** BOX | TOTE | BAG | CARTON | PALLET. */
    @Column(name = "shipper_type", nullable = false)
    private String shipperType;

    @Column(name = "length_mm")
    private BigDecimal lengthMm;

    @Column(name = "width_mm")
    private BigDecimal widthMm;

    @Column(name = "height_mm")
    private BigDecimal heightMm;

    @Column(name = "tare_weight_g")
    private BigDecimal tareWeightG;

    /** Usable fraction of the inner volume, range 0..1. */
    @Column(name = "max_fill_level", nullable = false)
    private BigDecimal maxFillLevel = BigDecimal.ONE;

    @Column(name = "max_weight_g")
    private BigDecimal maxWeightG;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShipperType() {
        return shipperType;
    }

    public void setShipperType(String shipperType) {
        this.shipperType = shipperType;
    }

    public BigDecimal getLengthMm() {
        return lengthMm;
    }

    public void setLengthMm(BigDecimal lengthMm) {
        this.lengthMm = lengthMm;
    }

    public BigDecimal getWidthMm() {
        return widthMm;
    }

    public void setWidthMm(BigDecimal widthMm) {
        this.widthMm = widthMm;
    }

    public BigDecimal getHeightMm() {
        return heightMm;
    }

    public void setHeightMm(BigDecimal heightMm) {
        this.heightMm = heightMm;
    }

    public BigDecimal getTareWeightG() {
        return tareWeightG;
    }

    public void setTareWeightG(BigDecimal tareWeightG) {
        this.tareWeightG = tareWeightG;
    }

    public BigDecimal getMaxFillLevel() {
        return maxFillLevel;
    }

    public void setMaxFillLevel(BigDecimal maxFillLevel) {
        this.maxFillLevel = maxFillLevel;
    }

    public BigDecimal getMaxWeightG() {
        return maxWeightG;
    }

    public void setMaxWeightG(BigDecimal maxWeightG) {
        this.maxWeightG = maxWeightG;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
