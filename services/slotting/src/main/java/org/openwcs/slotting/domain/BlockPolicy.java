package org.openwcs.slotting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Put-away scoring weights + redundancy/balance constraints for a storage block. The put-away
 * engine combines the weighted factors (velocity, consolidation, redundancy, balance) into one
 * location score; tuning the weights dials the consolidation-vs-redundancy trade-off.
 */
@Entity
@Table(name = "block_policy")
public class BlockPolicy extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "block_policy_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "w_velocity", nullable = false)
    private BigDecimal wVelocity = BigDecimal.ONE;

    @Column(name = "w_consolidation", nullable = false)
    private BigDecimal wConsolidation = BigDecimal.ONE;

    @Column(name = "w_redundancy", nullable = false)
    private BigDecimal wRedundancy = BigDecimal.ONE;

    @Column(name = "w_balance", nullable = false)
    private BigDecimal wBalance = BigDecimal.ONE;

    @Column(name = "default_max_aisle_pct", nullable = false)
    private BigDecimal defaultMaxAislePct = new BigDecimal("0.5");

    @Column(name = "min_aisles_a", nullable = false)
    private int minAislesA = 2;

    @Column(name = "min_aisles_b", nullable = false)
    private int minAislesB = 1;

    @Column(name = "min_aisles_c", nullable = false)
    private int minAislesC = 1;

    @Column(name = "reslot_enabled", nullable = false)
    private boolean reslotEnabled = false;

    @Column(name = "reslot_shift_pct", nullable = false)
    private BigDecimal reslotShiftPct = new BigDecimal("0.2");

    @Column(name = "offpeak_cron")
    private String offpeakCron;

    /** EWMA half-life (days) for the recency-weighted velocity score; smaller = more reactive. */
    @Column(name = "velocity_half_life_days", nullable = false)
    private BigDecimal velocityHalfLifeDays = new BigDecimal("14");

    /** Rank share assigned class A (top {@code abcAShare} of SKUs by decayed score). */
    @Column(name = "abc_a_share", nullable = false)
    private BigDecimal abcAShare = new BigDecimal("0.2");

    /** Rank share assigned class B (next {@code abcBShare}); the remainder is class C. */
    @Column(name = "abc_b_share", nullable = false)
    private BigDecimal abcBShare = new BigDecimal("0.3");

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBlockId() {
        return blockId;
    }

    public void setBlockId(UUID blockId) {
        this.blockId = blockId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public BigDecimal getWVelocity() {
        return wVelocity;
    }

    public void setWVelocity(BigDecimal wVelocity) {
        this.wVelocity = wVelocity;
    }

    public BigDecimal getWConsolidation() {
        return wConsolidation;
    }

    public void setWConsolidation(BigDecimal wConsolidation) {
        this.wConsolidation = wConsolidation;
    }

    public BigDecimal getWRedundancy() {
        return wRedundancy;
    }

    public void setWRedundancy(BigDecimal wRedundancy) {
        this.wRedundancy = wRedundancy;
    }

    public BigDecimal getWBalance() {
        return wBalance;
    }

    public void setWBalance(BigDecimal wBalance) {
        this.wBalance = wBalance;
    }

    public BigDecimal getDefaultMaxAislePct() {
        return defaultMaxAislePct;
    }

    public void setDefaultMaxAislePct(BigDecimal defaultMaxAislePct) {
        this.defaultMaxAislePct = defaultMaxAislePct;
    }

    public int getMinAislesA() {
        return minAislesA;
    }

    public void setMinAislesA(int minAislesA) {
        this.minAislesA = minAislesA;
    }

    public int getMinAislesB() {
        return minAislesB;
    }

    public void setMinAislesB(int minAislesB) {
        this.minAislesB = minAislesB;
    }

    public int getMinAislesC() {
        return minAislesC;
    }

    public void setMinAislesC(int minAislesC) {
        this.minAislesC = minAislesC;
    }

    public boolean isReslotEnabled() {
        return reslotEnabled;
    }

    public void setReslotEnabled(boolean reslotEnabled) {
        this.reslotEnabled = reslotEnabled;
    }

    public BigDecimal getReslotShiftPct() {
        return reslotShiftPct;
    }

    public void setReslotShiftPct(BigDecimal reslotShiftPct) {
        this.reslotShiftPct = reslotShiftPct;
    }

    public String getOffpeakCron() {
        return offpeakCron;
    }

    public void setOffpeakCron(String offpeakCron) {
        this.offpeakCron = offpeakCron;
    }

    public BigDecimal getVelocityHalfLifeDays() {
        return velocityHalfLifeDays;
    }

    public void setVelocityHalfLifeDays(BigDecimal velocityHalfLifeDays) {
        this.velocityHalfLifeDays = velocityHalfLifeDays;
    }

    public BigDecimal getAbcAShare() {
        return abcAShare;
    }

    public void setAbcAShare(BigDecimal abcAShare) {
        this.abcAShare = abcAShare;
    }

    public BigDecimal getAbcBShare() {
        return abcBShare;
    }

    public void setAbcBShare(BigDecimal abcBShare) {
        this.abcBShare = abcBShare;
    }
}
