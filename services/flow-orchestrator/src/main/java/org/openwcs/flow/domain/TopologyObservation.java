package org.openwcs.flow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A raw scan observation captured by a network sniffer (or replayed) for topology learning:
 * a handling unit ({@code barcode}) seen at a {@code node}, optionally tagged with the
 * {@code sourceIp} it was decoded from. Inference over these proposes a topology to confirm.
 */
@Entity
@Table(name = "topology_observation")
public class TopologyObservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "obs_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "node", nullable = false)
    private String node;

    @Column(name = "barcode", nullable = false)
    private String barcode;

    @Column(name = "source_ip")
    private String sourceIp;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    public UUID getId() {
        return id;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public void setWarehouseId(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public String getNode() {
        return node;
    }

    public void setNode(String node) {
        this.node = node;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public void setSourceIp(String sourceIp) {
        this.sourceIp = sourceIp;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public void setObservedAt(Instant observedAt) {
        this.observedAt = observedAt;
    }
}
