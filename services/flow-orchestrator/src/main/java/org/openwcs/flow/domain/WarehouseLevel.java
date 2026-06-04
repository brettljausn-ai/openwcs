package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A physical level (floor) of a warehouse in the automation topology / 3D placement model. Levels
 * are numbered per warehouse and carry a floor elevation in metres; placed equipment sits on a
 * level.
 */
@Entity
@Table(name = "warehouse_level")
public class WarehouseLevel extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "level_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "number", nullable = false)
    private int number;

    @Column(name = "name")
    private String name;

    /** Floor elevation in metres. */
    @Column(name = "elevation_m")
    private BigDecimal elevationM;

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

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getElevationM() {
        return elevationM;
    }

    public void setElevationM(BigDecimal elevationM) {
        this.elevationM = elevationM;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
