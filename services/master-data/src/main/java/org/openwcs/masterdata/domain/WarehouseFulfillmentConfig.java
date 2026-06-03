package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-warehouse outbound fulfilment policy (ADR 0002): which pick granularities are
 * allowed and how orders are cubed.
 */
@Entity
@Table(name = "warehouse_fulfillment_config")
public class WarehouseFulfillmentConfig extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "config_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false, unique = true)
    private UUID warehouseId;

    /** Allowed pick granularities: CASE | SPLIT_CASE | EACH. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_pick_types", nullable = false)
    private List<String> allowedPickTypes = new ArrayList<>(List.of("EACH"));

    /** APP (the WCS cubes) | ONE_TO_ONE (the host supplies the shipper instruction). */
    @Column(name = "cubing_mode", nullable = false)
    private String cubingMode = "APP";

    @Column(name = "default_shipper_id")
    private UUID defaultShipperId;

    // --- Batch (cluster) picking (ADR 0002 §6) ---

    @Column(name = "batch_enabled", nullable = false)
    private boolean batchEnabled;

    /** An order is batchable when its total pieces ≤ this (1 = single-item orders only). */
    @Column(name = "batch_max_pieces", nullable = false)
    private int batchMaxPieces = 1;

    /** Orders per pick tote. */
    @Column(name = "batch_max_orders", nullable = false)
    private int batchMaxOrders = 12;

    /** The shipper used as the pick tote. */
    @Column(name = "pick_tote_shipper_id")
    private UUID pickToteShipperId;

    /** Fallback dispatch-label template (label-template code) when an order/service sets none. */
    @Column(name = "default_label_template_code")
    private String defaultLabelTemplateCode;

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

    public List<String> getAllowedPickTypes() {
        return allowedPickTypes;
    }

    public void setAllowedPickTypes(List<String> allowedPickTypes) {
        this.allowedPickTypes = allowedPickTypes;
    }

    public String getCubingMode() {
        return cubingMode;
    }

    public void setCubingMode(String cubingMode) {
        this.cubingMode = cubingMode;
    }

    public UUID getDefaultShipperId() {
        return defaultShipperId;
    }

    public void setDefaultShipperId(UUID defaultShipperId) {
        this.defaultShipperId = defaultShipperId;
    }

    public boolean isBatchEnabled() {
        return batchEnabled;
    }

    public void setBatchEnabled(boolean batchEnabled) {
        this.batchEnabled = batchEnabled;
    }

    public int getBatchMaxPieces() {
        return batchMaxPieces;
    }

    public void setBatchMaxPieces(int batchMaxPieces) {
        this.batchMaxPieces = batchMaxPieces;
    }

    public int getBatchMaxOrders() {
        return batchMaxOrders;
    }

    public void setBatchMaxOrders(int batchMaxOrders) {
        this.batchMaxOrders = batchMaxOrders;
    }

    public UUID getPickToteShipperId() {
        return pickToteShipperId;
    }

    public void setPickToteShipperId(UUID pickToteShipperId) {
        this.pickToteShipperId = pickToteShipperId;
    }

    public String getDefaultLabelTemplateCode() {
        return defaultLabelTemplateCode;
    }

    public void setDefaultLabelTemplateCode(String defaultLabelTemplateCode) {
        this.defaultLabelTemplateCode = defaultLabelTemplateCode;
    }
}
