package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A mode-appropriate task line of a non-PICKING {@link WorkCycle} — the put-list generalised. The
 * PICKING flow keeps using {@link PutInstruction}; the other operating modes record their work and
 * outcome here:
 *
 * <ul>
 *   <li>{@code DECANT_MOVE} — move {@code expectedQty} of {@code skuId} from the cycle's source HU
 *       into {@code huId} (the target HU) {@code compartment}; on confirm {@code actualQty} is the
 *       moved qty.</li>
 *   <li>{@code COUNT_ENTRY} — count {@code skuId} in {@code huId}; {@code expectedQty} is the
 *       system qty, {@code actualQty} the counted qty, {@code variance} = actual − expected.</li>
 *   <li>{@code QC_VERDICT} — inspect {@code huId}/{@code skuId}; {@code verdict} ∈
 *       {PASS, FAIL, HOLD}.</li>
 *   <li>{@code MAINTENANCE_CHECK} — check carrier/HU {@code huId}; {@code verdict} ∈
 *       {OK, DEFECTIVE, REPAIR}.</li>
 * </ul>
 */
@Entity
@Table(name = "task_line")
public class TaskLine extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "task_line_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "work_cycle_id", nullable = false)
    private UUID workCycleId;

    /** DECANT_MOVE | COUNT_ENTRY | QC_VERDICT | MAINTENANCE_CHECK. */
    @Column(name = "line_type", nullable = false)
    private String lineType;

    /** HU this line concerns (target HU for DECANT_MOVE; inspected/counted/checked HU otherwise). */
    @Column(name = "hu_id")
    private UUID huId;

    /** SKU concerned; null for an HU-level MAINTENANCE check. */
    @Column(name = "sku_id")
    private UUID skuId;

    /** Target compartment (DECANT_MOVE). */
    @Column(name = "compartment")
    private String compartment;

    /** Expected/system qty (STOCK_COUNT) or the qty to move (DECANT_MOVE). */
    @Column(name = "expected_qty")
    private BigDecimal expectedQty;

    /** Counted qty (STOCK_COUNT) or moved qty (DECANT_MOVE), set on confirm. */
    @Column(name = "actual_qty")
    private BigDecimal actualQty;

    /** actualQty − expectedQty (STOCK_COUNT), set on confirm. */
    @Column(name = "variance")
    private BigDecimal variance;

    /** PASS|FAIL|HOLD (QC) or OK|DEFECTIVE|REPAIR (MAINTENANCE), set on confirm. */
    @Column(name = "verdict")
    private String verdict;

    /** Physical light/display id, when a destination light applies. */
    @Column(name = "put_light_id")
    private String putLightId;

    /** OPEN | CONFIRMED | CANCELLED. */
    @Column(name = "status", nullable = false)
    private String status = "OPEN";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details")
    private Map<String, Object> details;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkCycleId() {
        return workCycleId;
    }

    public void setWorkCycleId(UUID workCycleId) {
        this.workCycleId = workCycleId;
    }

    public String getLineType() {
        return lineType;
    }

    public void setLineType(String lineType) {
        this.lineType = lineType;
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

    public String getCompartment() {
        return compartment;
    }

    public void setCompartment(String compartment) {
        this.compartment = compartment;
    }

    public BigDecimal getExpectedQty() {
        return expectedQty;
    }

    public void setExpectedQty(BigDecimal expectedQty) {
        this.expectedQty = expectedQty;
    }

    public BigDecimal getActualQty() {
        return actualQty;
    }

    public void setActualQty(BigDecimal actualQty) {
        this.actualQty = actualQty;
    }

    public BigDecimal getVariance() {
        return variance;
    }

    public void setVariance(BigDecimal variance) {
        this.variance = variance;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getPutLightId() {
        return putLightId;
    }

    public void setPutLightId(String putLightId) {
        this.putLightId = putLightId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
