package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A storage/transport location, classified on two orthogonal axes (build.md §6):
 * {@code locationType} (physical form / what it holds) and {@code purpose}
 * (functional role in flows). The Flow Orchestrator uses {@code purpose} to pick
 * valid sources/destinations and {@code locationType} + capacity to validate fit.
 */
@Entity
@Table(name = "location")
public class Location extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "location_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    /** BIN, PALLET, FREE_SPACE, SHELF, GRID_BIN, ASRS_SLOT, CONVEYOR_SEGMENT, ROBOT_PORT, STATION. */
    @Column(name = "location_type", nullable = false)
    private String locationType;

    /** STORAGE, TRANSPORT, STAGING, PICK, PACK, INDUCT, RECEIVING, SHIPPING, QUARANTINE, RETURNS. */
    @Column(name = "purpose", nullable = false)
    private String purpose;

    /** Parent in the topology (zone / aisle / grid). */
    @Column(name = "parent_id")
    private UUID parentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coordinates")
    private Map<String, Object> coordinates;

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capacity")
    private Map<String, Object> capacity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_hu_types")
    private List<String> allowedHuTypes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_sku_attrs")
    private Map<String, Object> allowedSkuAttrs;

    @Column(name = "is_mixed_allowed", nullable = false)
    private boolean mixedAllowed = true;

    @Column(name = "replenishment_class")
    private String replenishmentClass;

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

    public String getLocationType() {
        return locationType;
    }

    public void setLocationType(String locationType) {
        this.locationType = locationType;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public Map<String, Object> getCoordinates() {
        return coordinates;
    }

    public void setCoordinates(Map<String, Object> coordinates) {
        this.coordinates = coordinates;
    }

    public UUID getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(UUID equipmentId) {
        this.equipmentId = equipmentId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getCapacity() {
        return capacity;
    }

    public void setCapacity(Map<String, Object> capacity) {
        this.capacity = capacity;
    }

    public List<String> getAllowedHuTypes() {
        return allowedHuTypes;
    }

    public void setAllowedHuTypes(List<String> allowedHuTypes) {
        this.allowedHuTypes = allowedHuTypes;
    }

    public Map<String, Object> getAllowedSkuAttrs() {
        return allowedSkuAttrs;
    }

    public void setAllowedSkuAttrs(Map<String, Object> allowedSkuAttrs) {
        this.allowedSkuAttrs = allowedSkuAttrs;
    }

    public boolean isMixedAllowed() {
        return mixedAllowed;
    }

    public void setMixedAllowed(boolean mixedAllowed) {
        this.mixedAllowed = mixedAllowed;
    }

    public String getReplenishmentClass() {
        return replenishmentClass;
    }

    public void setReplenishmentClass(String replenishmentClass) {
        this.replenishmentClass = replenishmentClass;
    }
}
