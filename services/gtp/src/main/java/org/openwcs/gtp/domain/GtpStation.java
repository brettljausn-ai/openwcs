package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A goods-to-person workplace/station. {@code mode} documents the physical realisation of its
 * order destinations — {@code ORDER_LOCATION} (order HUs in fixed/conveyor locations) or
 * {@code PUT_WALL} (a rack of lit cubbies, typical for AMR goods-to-rack). The execution shape
 * is identical in both modes.
 */
@Entity
@Table(name = "gtp_station")
public class GtpStation extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "gtp_station_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    /** ORDER_LOCATION | PUT_WALL. */
    @Column(name = "mode", nullable = false)
    private String mode = "ORDER_LOCATION";

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

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
