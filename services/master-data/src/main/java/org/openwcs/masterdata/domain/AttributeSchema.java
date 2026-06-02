package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Admin-defined schema (per warehouse + category) that governs the JSONB metadata
 * blobs elsewhere in master data (build.md §6 guardrail). {@code appliesTo} is one
 * of SKU | LOCATION | HU | EQUIPMENT.
 *
 * <p>Note: {@code version} here is the <em>business</em> schema version (admins
 * publish new versions), not an optimistic-lock column — so this entity does not
 * extend {@link Auditable}.
 */
@Entity
@Table(name = "attribute_schema")
public class AttributeSchema {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attribute_schema_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "applies_to", nullable = false)
    private String appliesTo;

    @Column(name = "category", nullable = false)
    private String category;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "json_schema", nullable = false)
    private Map<String, Object> jsonSchema = new HashMap<>();

    @Column(name = "version", nullable = false)
    private int schemaVersion = 1;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public String getAppliesTo() {
        return appliesTo;
    }

    public void setAppliesTo(String appliesTo) {
        this.appliesTo = appliesTo;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Map<String, Object> getJsonSchema() {
        return jsonSchema;
    }

    public void setJsonSchema(Map<String, Object> jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
