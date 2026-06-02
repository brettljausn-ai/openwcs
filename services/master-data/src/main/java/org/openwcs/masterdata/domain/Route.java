package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A dispatch route an outbound order is assigned to — e.g. CENTRAL_LONDON, MANCHESTER.
 * Routes are <b>transacted from a host system</b> (SAP / Manhattan): the host is the source of
 * truth and {@code hostRef} carries its id; this catalog caches them so orders can reference a
 * {@code routeCode} and the UI can resolve it. Global reference data; {@code code} is unique.
 */
@Entity
@Table(name = "route")
public class Route extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "route_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name")
    private String name;

    /** Geographic region / depot the route covers, e.g. "East London". */
    @Column(name = "region")
    private String region;

    /** Identifier of this route in the originating host system (source of truth). */
    @Column(name = "host_ref")
    private String hostRef;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getHostRef() {
        return hostRef;
    }

    public void setHostRef(String hostRef) {
        this.hostRef = hostRef;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
