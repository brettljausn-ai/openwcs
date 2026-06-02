package org.openwcs.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Cursor recording how far a projection has consumed the transaction log
 * (build.md §5.4). Lets consumers be idempotent (keyed on event id) and lets a
 * rebuild/replay resume from where it left off.
 */
@Entity
@Table(name = "projection_offset")
public class ProjectionOffset {

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
