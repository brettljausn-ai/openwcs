package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Handling-unit INSTANCE — the physical unit (pallet, tote, carton, ...) identified by
 * a barcode/{@code code}, of a given handling-unit type, currently parked in a location
 * and holding stock. This is instance data owned by the inventory service; {@code huTypeId}
 * references master_data.handling_unit_type and {@code locationId} references
 * master_data.location across the service boundary (no DB-level FK; build.md §5.3).
 *
 * <p>Stock rows reference a handling unit via {@code Stock.huId} = this {@code huId}.
 */
@Entity
@Table(name = "handling_unit")
public class HandlingUnit extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "hu_id", updatable = false, nullable = false)
    private UUID huId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    /** The HU barcode / identifier (unique per warehouse). */
    @Column(name = "code", nullable = false)
    private String code;

    /** ref master_data.handling_unit_type (no cross-service FK); nullable. */
    @Column(name = "hu_type_id")
    private UUID huTypeId;

    /** Current location (ref master_data.location); nullable when in transit / unparked. */
    @Column(name = "location_id")
    private UUID locationId;

    /** ACTIVE | EMPTY | IN_TRANSIT | RETIRED. */
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
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

    public UUID getHuTypeId() {
        return huTypeId;
    }

    public void setHuTypeId(UUID huTypeId) {
        this.huTypeId = huTypeId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
