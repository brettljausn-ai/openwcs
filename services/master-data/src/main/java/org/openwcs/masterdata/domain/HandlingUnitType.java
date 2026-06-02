package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** A type of handling unit — TOTE, TRAY, CARTON, PALLET … (build.md §6). */
@Entity
@Table(name = "handling_unit_type")
public class HandlingUnitType extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hu_type_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "length_mm")
    private BigDecimal lengthMm;

    @Column(name = "width_mm")
    private BigDecimal widthMm;

    @Column(name = "height_mm")
    private BigDecimal heightMm;

    @Column(name = "weight_limit_g")
    private BigDecimal weightLimitG;

    @Column(name = "nestable", nullable = false)
    private boolean nestable;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public BigDecimal getWeightLimitG() {
        return weightLimitG;
    }

    public void setWeightLimitG(BigDecimal weightLimitG) {
        this.weightLimitG = weightLimitG;
    }

    public boolean isNestable() {
        return nestable;
    }

    public void setNestable(boolean nestable) {
        this.nestable = nestable;
    }
}
