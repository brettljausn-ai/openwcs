package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A point in the conveyor topology where a handling unit is scanned and the WCS decides where
 * it goes next. {@code code} is the node id the hardware sends on a scan; {@code hardwareAddress}
 * is how that equipment is reached/identified; {@code posX}/{@code posY} position the node in the
 * admin schematic editor.
 */
@Entity
@Table(name = "conveyor_node")
public class ConveyorNode extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "node_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "hardware_address")
    private String hardwareAddress;

    /** The controller (PLC) hosting this node, by its code, or null. */
    @Column(name = "controller_code")
    private String controllerCode;

    /** The node-local address on its controller (e.g. slot/index), or null. */
    @Column(name = "node_address")
    private String nodeAddress;

    @Column(name = "pos_x")
    private Double posX;

    @Column(name = "pos_y")
    private Double posY;

    /** The loop this node belongs to (its code), or null if not in a loop. */
    @Column(name = "loop_code")
    private String loopCode;

    /** For a divert node: the code of the neighbour a tote takes by default when it has no route
     *  demanding otherwise (resolved from the divert's STRAIGHT/BRANCH choice), or null (stop). */
    @Column(name = "default_exit_code")
    private String defaultExitCode;

    public UUID getId() {
        return id;
    }

    public String getLoopCode() {
        return loopCode;
    }

    public void setLoopCode(String loopCode) {
        this.loopCode = loopCode;
    }

    public String getDefaultExitCode() {
        return defaultExitCode;
    }

    public void setDefaultExitCode(String defaultExitCode) {
        this.defaultExitCode = defaultExitCode;
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

    public String getHardwareAddress() {
        return hardwareAddress;
    }

    public void setHardwareAddress(String hardwareAddress) {
        this.hardwareAddress = hardwareAddress;
    }

    public String getControllerCode() {
        return controllerCode;
    }

    public void setControllerCode(String controllerCode) {
        this.controllerCode = controllerCode;
    }

    public String getNodeAddress() {
        return nodeAddress;
    }

    public void setNodeAddress(String nodeAddress) {
        this.nodeAddress = nodeAddress;
    }

    public Double getPosX() {
        return posX;
    }

    public void setPosX(Double posX) {
        this.posX = posX;
    }

    public Double getPosY() {
        return posY;
    }

    public void setPosY(Double posY) {
        this.posY = posY;
    }
}
