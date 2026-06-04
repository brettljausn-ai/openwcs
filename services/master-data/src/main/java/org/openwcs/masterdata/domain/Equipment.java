package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
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

    /** Automation-topology type: BIN_CONVEYOR | PALLET_CONVEYOR | LONGREACH_CONVEYOR | ASRS | SORTER. */
    @Column(name = "type")
    private String type;

    /** Subtype: ASRS -> SHUTTLE|CRANE|AMR|CUBE; SORTER -> CROSSBELT|TILTTRAY|SHOE; conveyors -> null. */
    @Column(name = "subtype")
    private String subtype;

    /** Default physical envelope (metres). For conveyors the length is set when placed in the topology. */
    @Column(name = "default_width_m")
    private BigDecimal defaultWidthM;

    @Column(name = "default_height_m")
    private BigDecimal defaultHeightM;

    @Column(name = "default_length_m")
    private BigDecimal defaultLengthM;

    /** Process / function types this equipment can carry: SCAN, LABEL_APPLICATOR, DIVERT_LEFT,
     *  DIVERT_RIGHT, DWS, QUERY_POINT, WRAPPER (mainly for conveyor sections). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "process_types")
    private List<String> processTypes;

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSubtype() {
        return subtype;
    }

    public void setSubtype(String subtype) {
        this.subtype = subtype;
    }

    public BigDecimal getDefaultWidthM() {
        return defaultWidthM;
    }

    public void setDefaultWidthM(BigDecimal defaultWidthM) {
        this.defaultWidthM = defaultWidthM;
    }

    public BigDecimal getDefaultHeightM() {
        return defaultHeightM;
    }

    public void setDefaultHeightM(BigDecimal defaultHeightM) {
        this.defaultHeightM = defaultHeightM;
    }

    public BigDecimal getDefaultLengthM() {
        return defaultLengthM;
    }

    public void setDefaultLengthM(BigDecimal defaultLengthM) {
        this.defaultLengthM = defaultLengthM;
    }

    public List<String> getProcessTypes() {
        return processTypes;
    }

    public void setProcessTypes(List<String> processTypes) {
        this.processTypes = processTypes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
