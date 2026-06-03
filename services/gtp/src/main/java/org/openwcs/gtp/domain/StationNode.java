package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A position at a GTP station. {@code role=STOCK} is a stock-HU presentation position;
 * {@code role=ORDER} is an order destination (an order-HU location in ORDER_LOCATION mode, or a
 * put-wall cubby in PUT_WALL mode) that carries an optional put-light and a currently bound
 * order HU.
 */
@Entity
@Table(name = "station_node")
public class StationNode extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "station_node_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "gtp_station_id", nullable = false)
    private UUID stationId;

    /** STOCK | ORDER. */
    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "code", nullable = false)
    private String code;

    /** Physical put-light / display id for an ORDER node (driven by a device adapter). */
    @Column(name = "put_light_id")
    private String putLightId;

    /** Master-data location, when this node maps to a fixed physical location. */
    @Column(name = "location_id")
    private UUID locationId;

    /** Order HU currently bound to an ORDER node. */
    @Column(name = "order_hu_id")
    private UUID orderHuId;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStationId() {
        return stationId;
    }

    public void setStationId(UUID stationId) {
        this.stationId = stationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getPutLightId() {
        return putLightId;
    }

    public void setPutLightId(String putLightId) {
        this.putLightId = putLightId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public UUID getOrderHuId() {
        return orderHuId;
    }

    public void setOrderHuId(UUID orderHuId) {
        this.orderHuId = orderHuId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
