package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A storage block — a group of storage locations slotted as one pool (ADR 0003).
 *
 * <p>For automated systems ({@code SHUTTLE_ASRS}, {@code CRANE_ASRS}, {@code AUTOSTORE},
 * {@code AMR_GTP}) the {@code slottingGranularity} is {@code BLOCK}: a SKU is slotted to the
 * whole block and the put-away engine chooses an actual location at put-away time. Manual pick
 * faces ({@code MANUAL_PICK}) use {@code LOCATION} granularity — one SKU+UoM per fixed location.
 * {@code gtp} marks goods-to-person blocks where HUs are retrieved to a pick station.
 */
@Entity
@Table(name = "storage_block")
public class StorageBlock extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "block_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    /** SHUTTLE_ASRS | CRANE_ASRS | AUTOSTORE | AMR_GTP | MANUAL_PICK | RESERVE_RACK. */
    @Column(name = "storage_type", nullable = false)
    private String storageType;

    /** BLOCK (automated pool) | LOCATION (fixed pick face). */
    @Column(name = "slotting_granularity", nullable = false)
    private String slottingGranularity = "BLOCK";

    @Column(name = "equipment_id")
    private UUID equipmentId;

    @Column(name = "is_gtp", nullable = false)
    private boolean gtp = false;

    /** HU type names this block (automated area) accepts; null/empty = accept any. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_hu_types")
    private List<String> allowedHuTypes;

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

    public String getStorageType() {
        return storageType;
    }

    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }

    public String getSlottingGranularity() {
        return slottingGranularity;
    }

    public void setSlottingGranularity(String slottingGranularity) {
        this.slottingGranularity = slottingGranularity;
    }

    public UUID getEquipmentId() {
        return equipmentId;
    }

    public void setEquipmentId(UUID equipmentId) {
        this.equipmentId = equipmentId;
    }

    public boolean isGtp() {
        return gtp;
    }

    public void setGtp(boolean gtp) {
        this.gtp = gtp;
    }

    public List<String> getAllowedHuTypes() {
        return allowedHuTypes;
    }

    public void setAllowedHuTypes(List<String> allowedHuTypes) {
        this.allowedHuTypes = allowedHuTypes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
