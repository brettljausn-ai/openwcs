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
 * Unit of measure ("bundle") in a per-SKU hierarchy with conversion factors
 * (build.md §6). The base unit is the smallest stockable unit; all stock math
 * normalizes to it (build.md §12).
 */
@Entity
@Table(name = "unit_of_measure")
public class UnitOfMeasure extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "uom_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "code", nullable = false)
    private String code;

    /** Parent UoM in the bundle hierarchy (e.g. CASE is the parent of EACH). */
    @Column(name = "parent_uom_id")
    private UUID parentUomId;

    /** Conversion factor: how many of this UoM make up one parent UoM. */
    @Column(name = "qty_in_parent")
    private BigDecimal qtyInParent;

    @Column(name = "length_mm")
    private BigDecimal lengthMm;

    @Column(name = "width_mm")
    private BigDecimal widthMm;

    @Column(name = "height_mm")
    private BigDecimal heightMm;

    @Column(name = "weight_g")
    private BigDecimal weightG;

    @Column(name = "is_base_unit", nullable = false)
    private boolean baseUnit;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public UUID getParentUomId() {
        return parentUomId;
    }

    public void setParentUomId(UUID parentUomId) {
        this.parentUomId = parentUomId;
    }

    public BigDecimal getQtyInParent() {
        return qtyInParent;
    }

    public void setQtyInParent(BigDecimal qtyInParent) {
        this.qtyInParent = qtyInParent;
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

    public BigDecimal getWeightG() {
        return weightG;
    }

    public void setWeightG(BigDecimal weightG) {
        this.weightG = weightG;
    }

    public boolean isBaseUnit() {
        return baseUnit;
    }

    public void setBaseUnit(boolean baseUnit) {
        this.baseUnit = baseUnit;
    }
}
