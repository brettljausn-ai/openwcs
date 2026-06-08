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

    /** Number of compartments (1–8); each compartment can hold a different SKU. */
    @Column(name = "compartments", nullable = false)
    private int compartments = 1;

    /** Whether this HU type may be stored inside an automated system (ASRS/AutoStore/AMR-GTP). */
    @Column(name = "storable_in_automation", nullable = false)
    private boolean storableInAutomation = true;

    /** Whether this HU type can travel on a conveyor (some are conveyable but not storable). */
    @Column(name = "transportable_on_conveyor", nullable = false)
    private boolean transportableOnConveyor = true;

    /**
     * Whether this HU type is also a shipper: a unit that leaves the warehouse with the goods
     * (e.g. a shipping carton/tote) and is therefore a candidate target for outbound cubing.
     */
    @Column(name = "is_shipper", nullable = false)
    private boolean shipper = false;

    /** Lifecycle status: ACTIVE or ARCHIVED. Archived types are kept for history but no longer used. */
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

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

    public int getCompartments() {
        return compartments;
    }

    public void setCompartments(int compartments) {
        this.compartments = compartments;
    }

    public boolean isStorableInAutomation() {
        return storableInAutomation;
    }

    public void setStorableInAutomation(boolean storableInAutomation) {
        this.storableInAutomation = storableInAutomation;
    }

    public boolean isTransportableOnConveyor() {
        return transportableOnConveyor;
    }

    public void setTransportableOnConveyor(boolean transportableOnConveyor) {
        this.transportableOnConveyor = transportableOnConveyor;
    }

    public boolean isShipper() {
        return shipper;
    }

    public void setShipper(boolean shipper) {
        this.shipper = shipper;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
