package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Daily scan-quality counter per scan point (Reporting): how many scans the node answered, how
 * many were scanner read errors (blank / NOREAD) and how many carried a readable barcode with no
 * active route plan. One row per (warehouse, node, day); rows are bumped by an atomic SQL upsert
 * on every scan ({@link org.openwcs.flow.repo.ScanStatRepository#bump}), never read-modify-write.
 */
@Entity
@Table(name = "scan_stat")
@IdClass(ScanStat.Key.class)
public class ScanStat {

    @Id
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Id
    @Column(name = "node_code", nullable = false)
    private String nodeCode;

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "scans", nullable = false)
    private long scans;

    @Column(name = "no_reads", nullable = false)
    private long noReads;

    @Column(name = "unknowns", nullable = false)
    private long unknowns;

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public String getNodeCode() {
        return nodeCode;
    }

    public LocalDate getDay() {
        return day;
    }

    public long getScans() {
        return scans;
    }

    public long getNoReads() {
        return noReads;
    }

    public long getUnknowns() {
        return unknowns;
    }

    /** Composite key (warehouse, node, day). */
    public static class Key implements Serializable {

        private UUID warehouseId;
        private String nodeCode;
        private LocalDate day;

        public Key() {
        }

        public Key(UUID warehouseId, String nodeCode, LocalDate day) {
            this.warehouseId = warehouseId;
            this.nodeCode = nodeCode;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(warehouseId, k.warehouseId)
                    && Objects.equals(nodeCode, k.nodeCode) && Objects.equals(day, k.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(warehouseId, nodeCode, day);
        }
    }
}
