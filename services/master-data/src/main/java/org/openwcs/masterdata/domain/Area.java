package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A first-class, hierarchical grouping of locations within a warehouse (zone, sub-zone, aisle
 * group, ...). An area may nest under a parent area in the same warehouse, forming a tree
 * (parent is null at the root). Locations point at the area they belong to via
 * {@code Location.areaId}; other services resolve an area by code and expand it to the set of
 * location ids in its subtree (the area plus all descendant areas).
 */
@Entity
@Table(name = "area")
public class Area extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "area_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    /** Parent in the area tree (same warehouse); null at the root. */
    @Column(name = "parent_area_id")
    private UUID parentAreaId;

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

    public UUID getParentAreaId() {
        return parentAreaId;
    }

    public void setParentAreaId(UUID parentAreaId) {
        this.parentAreaId = parentAreaId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
