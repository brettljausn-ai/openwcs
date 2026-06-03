package org.openwcs.flow.domain;

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
 * The active route plan for a handling unit (by barcode): the ordered target node codes it must
 * visit and how far it has progressed. As the HU is scanned at each target, {@code currentIndex}
 * advances; when it passes the last target the route is COMPLETED.
 */
@Entity
@Table(name = "hu_route")
public class HuRoute extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "route_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "barcode", nullable = false)
    private String barcode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "targets", nullable = false)
    private List<String> targets = new ArrayList<>();

    @Column(name = "current_index", nullable = false)
    private int currentIndex;

    /** ACTIVE | COMPLETED | EXCEPTION. */
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";

    @Column(name = "detail")
    private String detail;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public List<String> getTargets() {
        return targets;
    }

    public void setTargets(List<String> targets) {
        this.targets = targets;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }
}
