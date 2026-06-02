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
 * Hazardous-goods classification, 0..1 per SKU (build.md §6). Drives storage
 * segregation and handling constraints used by the Flow Orchestrator (§4.5).
 */
@Entity
@Table(name = "dangerous_goods")
public class DangerousGoods extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "dg_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false, unique = true)
    private UUID skuId;

    @Column(name = "un_number")
    private String unNumber;

    @Column(name = "hazard_class")
    private String hazardClass;

    @Column(name = "packing_group")
    private String packingGroup;

    @Column(name = "proper_shipping_name")
    private String properShippingName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adr_imdg_iata_codes")
    private List<String> adrImdgIataCodes;

    @Column(name = "flash_point")
    private BigDecimal flashPoint;

    @Column(name = "net_explosive_qty")
    private BigDecimal netExplosiveQty;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storage_segregation_rules")
    private Map<String, Object> storageSegregationRules;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "handling_constraints")
    private Map<String, Object> handlingConstraints;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public String getUnNumber() {
        return unNumber;
    }

    public void setUnNumber(String unNumber) {
        this.unNumber = unNumber;
    }

    public String getHazardClass() {
        return hazardClass;
    }

    public void setHazardClass(String hazardClass) {
        this.hazardClass = hazardClass;
    }

    public String getPackingGroup() {
        return packingGroup;
    }

    public void setPackingGroup(String packingGroup) {
        this.packingGroup = packingGroup;
    }

    public String getProperShippingName() {
        return properShippingName;
    }

    public void setProperShippingName(String properShippingName) {
        this.properShippingName = properShippingName;
    }

    public List<String> getAdrImdgIataCodes() {
        return adrImdgIataCodes;
    }

    public void setAdrImdgIataCodes(List<String> adrImdgIataCodes) {
        this.adrImdgIataCodes = adrImdgIataCodes;
    }

    public BigDecimal getFlashPoint() {
        return flashPoint;
    }

    public void setFlashPoint(BigDecimal flashPoint) {
        this.flashPoint = flashPoint;
    }

    public BigDecimal getNetExplosiveQty() {
        return netExplosiveQty;
    }

    public void setNetExplosiveQty(BigDecimal netExplosiveQty) {
        this.netExplosiveQty = netExplosiveQty;
    }

    public Map<String, Object> getStorageSegregationRules() {
        return storageSegregationRules;
    }

    public void setStorageSegregationRules(Map<String, Object> storageSegregationRules) {
        this.storageSegregationRules = storageSegregationRules;
    }

    public Map<String, Object> getHandlingConstraints() {
        return handlingConstraints;
    }

    public void setHandlingConstraints(Map<String, Object> handlingConstraints) {
        this.handlingConstraints = handlingConstraints;
    }
}
