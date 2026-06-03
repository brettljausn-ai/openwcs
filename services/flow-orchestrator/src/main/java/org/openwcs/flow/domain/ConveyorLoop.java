package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A looping conveyor section with a maximum number of handling units. When a loop is at
 * capacity, an HU that would enter it is HELD (waits upstream) or diverted to
 * {@code overflowTargetCode} (OVERFLOW).
 */
@Entity
@Table(name = "conveyor_loop")
public class ConveyorLoop extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "loop_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "max_hus", nullable = false)
    private int maxHus;

    /** HOLD | OVERFLOW. */
    @Column(name = "when_full", nullable = false)
    private String whenFull = "HOLD";

    @Column(name = "overflow_target_code")
    private String overflowTargetCode;

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

    public int getMaxHus() {
        return maxHus;
    }

    public void setMaxHus(int maxHus) {
        this.maxHus = maxHus;
    }

    public String getWhenFull() {
        return whenFull;
    }

    public void setWhenFull(String whenFull) {
        this.whenFull = whenFull;
    }

    public String getOverflowTargetCode() {
        return overflowTargetCode;
    }

    public void setOverflowTargetCode(String overflowTargetCode) {
        this.overflowTargetCode = overflowTargetCode;
    }
}
