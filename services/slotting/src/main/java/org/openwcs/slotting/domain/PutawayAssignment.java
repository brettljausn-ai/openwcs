package org.openwcs.slotting.domain;

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

/** Audit record of a put-away decision: which location the engine chose for an HU and why. */
@Entity
@Table(name = "putaway_assignment")
public class PutawayAssignment extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "putaway_assignment_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "hu_id")
    private UUID huId;

    /** Dominant compartment SKU (or the single SKU); null for an empty HU. */
    @Column(name = "sku_id")
    private UUID skuId;

    /** Full compartment SKU set (lane-affinity key); single = [skuId], empty HU = []. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sku_ids")
    private List<UUID> skuIds;

    @Column(name = "block_id")
    private UUID blockId;

    @Column(name = "chosen_location_id")
    private UUID chosenLocationId;

    /** RESERVE | DIRECT_TO_PICK. */
    @Column(name = "mode", nullable = false)
    private String mode = "RESERVE";

    @Column(name = "score")
    private BigDecimal score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factors")
    private Map<String, Object> factors;

    @Column(name = "status", nullable = false)
    private String status = "PLANNED";

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

    public UUID getHuId() {
        return huId;
    }

    public void setHuId(UUID huId) {
        this.huId = huId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public List<UUID> getSkuIds() {
        return skuIds;
    }

    public void setSkuIds(List<UUID> skuIds) {
        this.skuIds = skuIds;
    }

    public UUID getBlockId() {
        return blockId;
    }

    public void setBlockId(UUID blockId) {
        this.blockId = blockId;
    }

    public UUID getChosenLocationId() {
        return chosenLocationId;
    }

    public void setChosenLocationId(UUID chosenLocationId) {
        this.chosenLocationId = chosenLocationId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public Map<String, Object> getFactors() {
        return factors;
    }

    public void setFactors(Map<String, Object> factors) {
        this.factors = factors;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
