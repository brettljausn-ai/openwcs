package org.openwcs.gtp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An operator session on a GTP station (a <em>workplace</em>). Exactly one session may be
 * {@code ACTIVE} per workplace at a time — claiming a workplace that already has an active session
 * {@code SUPERSEDES} the previous one so the prior operator's console can detect the takeover and
 * stop. The console keeps its session alive with heartbeats and {@code RELEASE}s it on close.
 *
 * <p>{@code status}: {@code ACTIVE} (the live owner), {@code SUPERSEDED} (taken over by a newer
 * claim), {@code RELEASED} (cleanly released by its own operator).
 */
@Entity
@Table(name = "workplace_session")
public class WorkplaceSession extends Auditable {

    public static final String ACTIVE = "ACTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "workplace_session_id", updatable = false, nullable = false)
    private UUID id;

    /** The GTP station (workplace) this session owns. */
    @Column(name = "gtp_station_id", nullable = false)
    private UUID stationId;

    /** Who claimed it (from the X-Auth-User header), when known. */
    @Column(name = "operator")
    private String operator;

    /** ACTIVE | SUPERSEDED | RELEASED. */
    @Column(name = "status", nullable = false)
    private String status = ACTIVE;

    /** Why the session closed (e.g. {@code superseded}, {@code released}). */
    @Column(name = "superseded_reason")
    private String supersededReason;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt = Instant.now();

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStationId() {
        return stationId;
    }

    public void setStationId(UUID stationId) {
        this.stationId = stationId;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSupersededReason() {
        return supersededReason;
    }

    public void setSupersededReason(String supersededReason) {
        this.supersededReason = supersededReason;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }
}
