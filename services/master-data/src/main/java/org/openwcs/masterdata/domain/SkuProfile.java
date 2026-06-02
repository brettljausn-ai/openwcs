package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Per-warehouse overlay for a SKU (build.md §6). Holds ALL site-variable attributes
 * in {@code metadata} and the storage "teach-in" in {@code storageStrategy}; the
 * metadata is validated on write against the referenced {@link AttributeSchema}.
 * One row per (sku, warehouse).
 */
@Entity
@Table(name = "sku_profile")
public class SkuProfile extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "sku_profile_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storage_strategy", nullable = false)
    private Map<String, Object> storageStrategy = new HashMap<>();

    @Column(name = "attribute_schema_id")
    private UUID attributeSchemaId;

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

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public Map<String, Object> getStorageStrategy() {
        return storageStrategy;
    }

    public void setStorageStrategy(Map<String, Object> storageStrategy) {
        this.storageStrategy = storageStrategy;
    }

    public UUID getAttributeSchemaId() {
        return attributeSchemaId;
    }

    public void setAttributeSchemaId(UUID attributeSchemaId) {
        this.attributeSchemaId = attributeSchemaId;
    }
}
