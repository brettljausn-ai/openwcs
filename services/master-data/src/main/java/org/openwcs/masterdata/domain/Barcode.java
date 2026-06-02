package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A barcode value identifying a SKU at a given packaging level (UoM). build.md §6. */
@Entity
@Table(name = "barcode")
public class Barcode extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "barcode_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    /** Which packaging level (UoM) this barcode identifies. */
    @Column(name = "uom_id")
    private UUID uomId;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "barcode_type_id")
    private UUID barcodeTypeId;

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

    public UUID getUomId() {
        return uomId;
    }

    public void setUomId(UUID uomId) {
        this.uomId = uomId;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public UUID getBarcodeTypeId() {
        return barcodeTypeId;
    }

    public void setBarcodeTypeId(UUID barcodeTypeId) {
        this.barcodeTypeId = barcodeTypeId;
    }
}
