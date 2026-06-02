package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A piece of material-handling equipment (build.md §6). {@code family} is one of
 * CONVEYOR | ASRS | AMR | AUTOSTORE; the adapter endpoint points at the Go device
 * adapter that speaks the vendor protocol (build.md §8/§9).
 */
@Entity
@Table(name = "equipment")
public class Equipment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "equipment_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "family", nullable = false)
    private String family;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "model")
    private String model;

    @Column(name = "adapter_endpoint")
    private String adapterEndpoint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "capabilities")
    private Map<String, Object> capabilities;

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

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAdapterEndpoint() {
        return adapterEndpoint;
    }

    public void setAdapterEndpoint(String adapterEndpoint) {
        this.adapterEndpoint = adapterEndpoint;
    }

    public Map<String, Object> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Map<String, Object> capabilities) {
        this.capabilities = capabilities;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
