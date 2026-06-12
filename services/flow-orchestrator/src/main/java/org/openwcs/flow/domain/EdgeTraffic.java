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
 * Daily conveyor traffic counter per directed edge (Reporting heatmap): how many ROUTE answers
 * sent a handling unit from {@code fromNode} to {@code toNode} that day (including divert-default
 * routes). One row per (warehouse, from, to, day); bumped by an atomic SQL upsert per ROUTE
 * ({@link org.openwcs.flow.repo.EdgeTrafficRepository#bump}), never read-modify-write.
 */
@Entity
@Table(name = "edge_traffic")
@IdClass(EdgeTraffic.Key.class)
public class EdgeTraffic {

    @Id
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Id
    @Column(name = "from_node", nullable = false)
    private String fromNode;

    @Id
    @Column(name = "to_node", nullable = false)
    private String toNode;

    @Id
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "count", nullable = false)
    private long count;

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public String getFromNode() {
        return fromNode;
    }

    public String getToNode() {
        return toNode;
    }

    public LocalDate getDay() {
        return day;
    }

    public long getCount() {
        return count;
    }

    /** Composite key (warehouse, from, to, day). */
    public static class Key implements Serializable {

        private UUID warehouseId;
        private String fromNode;
        private String toNode;
        private LocalDate day;

        public Key() {
        }

        public Key(UUID warehouseId, String fromNode, String toNode, LocalDate day) {
            this.warehouseId = warehouseId;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.day = day;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(warehouseId, k.warehouseId)
                    && Objects.equals(fromNode, k.fromNode) && Objects.equals(toNode, k.toNode)
                    && Objects.equals(day, k.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(warehouseId, fromNode, toNode, day);
        }
    }
}
