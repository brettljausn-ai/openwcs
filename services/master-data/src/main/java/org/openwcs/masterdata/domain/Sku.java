package org.openwcs.masterdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * SKU global core — stable, warehouse-independent identity (build.md §6, §16).
 * Site-variable attributes live in {@link SkuProfile}; instance data (batch/serial)
 * lives in the inventory service. The tracking flags declare what goods-in must capture.
 */
@Entity
@Table(name = "sku")
public class Sku extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "sku_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true)
    private String code;

    @Column(name = "description")
    private String description;

    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "owner_client")
    private String ownerClient;

    @Column(name = "is_batch_tracked", nullable = false)
    private boolean batchTracked;

    @Column(name = "is_serial_tracked", nullable = false)
    private boolean serialTracked;

    @Column(name = "is_date_tracked", nullable = false)
    private boolean dateTracked;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOwnerClient() {
        return ownerClient;
    }

    public void setOwnerClient(String ownerClient) {
        this.ownerClient = ownerClient;
    }

    public boolean isBatchTracked() {
        return batchTracked;
    }

    public void setBatchTracked(boolean batchTracked) {
        this.batchTracked = batchTracked;
    }

    public boolean isSerialTracked() {
        return serialTracked;
    }

    public void setSerialTracked(boolean serialTracked) {
        this.serialTracked = serialTracked;
    }

    public boolean isDateTracked() {
        return dateTracked;
    }

    public void setDateTracked(boolean dateTracked) {
        this.dateTracked = dateTracked;
    }
}
