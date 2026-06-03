package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A dispatch service level offered on outbound orders — e.g. EXPRESS, STANDARD — optionally
 * tied to a carrier. Referenced by an order's {@code serviceCode}. Global reference data
 * (not warehouse-scoped); {@code code} is unique.
 */
@Entity
@Table(name = "shipping_service")
public class ShippingService extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "service_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name")
    private String name;

    /** Carrier that operates this service, e.g. DPD, ROYAL_MAIL. */
    @Column(name = "carrier")
    private String carrier;

    /** Default dispatch-label template (master-data label-template code) for this service. */
    @Column(name = "label_template_code")
    private String labelTemplateCode;

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

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getLabelTemplateCode() {
        return labelTemplateCode;
    }

    public void setLabelTemplateCode(String labelTemplateCode) {
        this.labelTemplateCode = labelTemplateCode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
