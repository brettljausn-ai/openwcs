package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A dispatch-label design authored in the admin UI: a labelled, sized canvas with an ordered
 * list of {@link LabelElement}s (address blocks, names, barcodes, images). Applied to a
 * shipper at dispatch and rendered to a print format (ZPL/PDF) via the render endpoint.
 * Global reference data; {@code code} is unique.
 */
@Entity
@Table(name = "label_template")
public class LabelTemplate extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "template_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "name")
    private String name;

    @Column(name = "width_mm", nullable = false)
    private BigDecimal widthMm;

    @Column(name = "height_mm", nullable = false)
    private BigDecimal heightMm;

    /** Print resolution for ZPL (dots/inch); typical thermal label printers are 203 or 300. */
    @Column(name = "dpi", nullable = false)
    private int dpi = 203;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "elements", nullable = false)
    private List<LabelElement> elements = new ArrayList<>();

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

    public BigDecimal getWidthMm() {
        return widthMm;
    }

    public void setWidthMm(BigDecimal widthMm) {
        this.widthMm = widthMm;
    }

    public BigDecimal getHeightMm() {
        return heightMm;
    }

    public void setHeightMm(BigDecimal heightMm) {
        this.heightMm = heightMm;
    }

    public int getDpi() {
        return dpi;
    }

    public void setDpi(int dpi) {
        this.dpi = dpi;
    }

    public List<LabelElement> getElements() {
        return elements;
    }

    public void setElements(List<LabelElement> elements) {
        this.elements = elements;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
