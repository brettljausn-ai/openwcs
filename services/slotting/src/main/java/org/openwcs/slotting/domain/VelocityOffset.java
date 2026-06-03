package org.openwcs.slotting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Consumption cursor for the velocity projection (mirrors {@code inventory.projection_offset},
 * build.md §5.4). Records how far the velocity consumer has read the transaction log so progress
 * is observable and a replay can resume.
 */
@Entity
@Table(name = "velocity_offset")
public class VelocityOffset {

    @Id
    @Column(name = "projection", nullable = false)
    private String projection;

    @Column(name = "last_event_id")
    private UUID lastEventId;

    @Column(name = "last_seq")
    private Long lastSeq;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getProjection() {
        return projection;
    }

    public void setProjection(String projection) {
        this.projection = projection;
    }

    public UUID getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(UUID lastEventId) {
        this.lastEventId = lastEventId;
    }

    public Long getLastSeq() {
        return lastSeq;
    }

    public void setLastSeq(Long lastSeq) {
        this.lastSeq = lastSeq;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
