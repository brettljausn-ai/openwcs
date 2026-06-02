package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Barcode symbology/type (build.md §6). Drives parsing/validation — e.g. GS1-128
 * application-identifier parsing to extract batch/expiry/SSCC at goods-in.
 */
@Entity
@Table(name = "barcode_type")
public class BarcodeType extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "barcode_type_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true)
    private String name;

    @Column(name = "symbology")
    private String symbology;

    @Column(name = "length_rule")
    private String lengthRule;

    @Column(name = "check_digit_rule")
    private String checkDigitRule;

    @Column(name = "gs1_ai_parsing", nullable = false)
    private boolean gs1AiParsing;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSymbology() {
        return symbology;
    }

    public void setSymbology(String symbology) {
        this.symbology = symbology;
    }

    public String getLengthRule() {
        return lengthRule;
    }

    public void setLengthRule(String lengthRule) {
        this.lengthRule = lengthRule;
    }

    public String getCheckDigitRule() {
        return checkDigitRule;
    }

    public void setCheckDigitRule(String checkDigitRule) {
        this.checkDigitRule = checkDigitRule;
    }

    public boolean isGs1AiParsing() {
        return gs1AiParsing;
    }

    public void setGs1AiParsing(boolean gs1AiParsing) {
        this.gs1AiParsing = gs1AiParsing;
    }
}
