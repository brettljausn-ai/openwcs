package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A conveyor controller (PLC). One PLC typically hosts MANY nodes: it is reached at a single
 * {@code ipAddress}/{@code port} and identified per-warehouse by its {@code code}. Nodes reference
 * a controller by code plus a node-local address instead of each carrying a full hardware address.
 */
@Entity
@Table(name = "conveyor_controller")
public class ConveyorController extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "controller_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "port")
    private Integer port;

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

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }
}
