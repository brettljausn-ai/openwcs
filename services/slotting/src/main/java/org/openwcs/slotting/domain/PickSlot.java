package org.openwcs.slotting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A fixed manual/forward pick face: one SKU+UoM assigned to a specific location, with min/max
 * levels that drive replenishment. {@code directToPick} lets inbound be routed straight here.
 */
@Entity
@Table(name = "pick_slot")
public class PickSlot extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "pick_slot_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "uom_id", nullable = false)
    private UUID uomId;

    @Column(name = "min_qty", nullable = false)
    private BigDecimal minQty = BigDecimal.ZERO;

    @Column(name = "max_qty", nullable = false)
    private BigDecimal maxQty;

    @Column(name = "direct_to_pick", nullable = false)
    private boolean directToPick = false;

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

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
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

    public BigDecimal getMinQty() {
        return minQty;
    }

    public void setMinQty(BigDecimal minQty) {
        this.minQty = minQty;
    }

    public BigDecimal getMaxQty() {
        return maxQty;
    }

    public void setMaxQty(BigDecimal maxQty) {
        this.maxQty = maxQty;
    }

    public boolean isDirectToPick() {
        return directToPick;
    }

    public void setDirectToPick(boolean directToPick) {
        this.directToPick = directToPick;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
