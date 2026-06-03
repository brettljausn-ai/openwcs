package org.openwcs.gtp.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openwcs.gtp.api.NotFoundException;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.WorkplaceSession;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.WorkplaceSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-active-session-per-workplace lifecycle for the GTP operator console. A GTP station is a
 * workplace; an operator <em>claims</em> it to get a session, <em>heartbeats</em> to keep it alive,
 * and <em>releases</em> it on close. At most one session is {@link WorkplaceSession#ACTIVE ACTIVE}
 * per workplace: a new claim supersedes the previous active session (marking it
 * {@link WorkplaceSession#SUPERSEDED SUPERSEDED}) so the prior operator's console can detect — via
 * its heartbeat — that it was taken over, and stop.
 */
@Service
public class WorkplaceSessionService {

    /** Reason recorded (and reported to the superseded console) when a session is taken over. */
    public static final String REASON_SUPERSEDED = "superseded";
    /** Reason recorded when a session is cleanly released by its own operator. */
    public static final String REASON_RELEASED = "released";

    private final GtpStationRepository stations;
    private final WorkplaceSessionRepository sessions;

    public WorkplaceSessionService(GtpStationRepository stations,
                                   WorkplaceSessionRepository sessions) {
        this.stations = stations;
        this.sessions = sessions;
    }

    /**
     * Claim a workplace for an operator. Any existing active session for the workplace is superseded
     * first, then a fresh active session is created and returned.
     */
    @Transactional
    public WorkplaceSession claim(UUID stationId, String operator) {
        requireStation(stationId);

        sessions.findByStationIdAndStatus(stationId, WorkplaceSession.ACTIVE).ifPresent(prev -> {
            prev.setStatus(WorkplaceSession.SUPERSEDED);
            prev.setSupersededReason(REASON_SUPERSEDED);
            prev.setClosedAt(Instant.now());
        });
        // Release the freed ACTIVE slot before inserting the new one (the partial unique index
        // allows only a single ACTIVE row per workplace).
        sessions.flush();

        WorkplaceSession session = new WorkplaceSession();
        session.setStationId(stationId);
        session.setOperator(operator);
        session.setStatus(WorkplaceSession.ACTIVE);
        Instant now = Instant.now();
        session.setClaimedAt(now);
        session.setLastHeartbeatAt(now);
        return sessions.save(session);
    }

    /**
     * Keep-alive for a session. Returns the session if it is still active (heartbeat refreshed); if
     * it was superseded or released the stored row is returned unchanged so the caller can report
     * {@code active:false} with the reason.
     */
    @Transactional
    public WorkplaceSession heartbeat(UUID stationId, UUID sessionId) {
        WorkplaceSession session = requireSession(stationId, sessionId);
        if (session.isActive()) {
            session.setLastHeartbeatAt(Instant.now());
        }
        return session;
    }

    /** Release a session cleanly (e.g. the console is closed). Idempotent for an already-closed one. */
    @Transactional
    public WorkplaceSession release(UUID stationId, UUID sessionId) {
        WorkplaceSession session = requireSession(stationId, sessionId);
        if (session.isActive()) {
            session.setStatus(WorkplaceSession.RELEASED);
            session.setSupersededReason(REASON_RELEASED);
            session.setClosedAt(Instant.now());
        }
        return session;
    }

    @Transactional(readOnly = true)
    public WorkplaceSession requireSession(UUID stationId, UUID sessionId) {
        WorkplaceSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("workplace session", sessionId));
        if (!session.getStationId().equals(stationId)) {
            throw new NotFoundException("workplace session", sessionId);
        }
        return session;
    }

    /** The station ids (within the given set) that currently have an active session. */
    @Transactional(readOnly = true)
    public Set<UUID> activeStationIds(List<UUID> stationIds) {
        if (stationIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> active = new HashSet<>();
        for (WorkplaceSession s : sessions.findByStationIdInAndStatus(stationIds, WorkplaceSession.ACTIVE)) {
            active.add(s.getStationId());
        }
        return active;
    }

    private GtpStation requireStation(UUID stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("station", stationId));
    }
}
