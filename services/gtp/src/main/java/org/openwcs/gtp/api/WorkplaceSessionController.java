package org.openwcs.gtp.api;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.WorkplaceSession;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.WorkplaceSessionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The GTP operator console's workplace + single-active-session-per-workplace API. A workplace is a
 * GTP station; opening one <em>claims</em> a session (superseding any session already on that
 * workplace), the console <em>heartbeats</em> to keep it alive and learn if it was taken over, and
 * <em>releases</em> it on close. A streaming endpoint pushes a {@code superseded} event as a nicer
 * alternative to polling.
 */
@RestController
@RequestMapping("/api/gtp/workplaces")
public class WorkplaceSessionController {

    private final GtpStationService stations;
    private final WorkplaceSessionService sessionService;

    public WorkplaceSessionController(GtpStationService stations,
                                      WorkplaceSessionService sessionService) {
        this.stations = stations;
        this.sessionService = sessionService;
    }

    /** List the GTP workplaces in a warehouse to pick from, flagging which are currently in use. */
    @GetMapping
    public List<WorkplaceView> list(@RequestParam("warehouseId") UUID warehouseId) {
        List<GtpStation> all = stations.byWarehouse(warehouseId);
        Set<UUID> inUse = sessionService.activeStationIds(all.stream().map(GtpStation::getId).toList());
        return all.stream()
                .map(s -> WorkplaceView.from(s, stations.nodesOf(s.getId()), inUse.contains(s.getId())))
                .toList();
    }

    /** Get a single workplace and its nodes (the work context), flagging whether it is in use. */
    @GetMapping("/{stationId}")
    public WorkplaceView get(@PathVariable UUID stationId) {
        return workplaceOf(stations.requireStation(stationId));
    }

    /**
     * Claim a workplace → returns a sessionId and the work context. Supersedes any existing active
     * session for the workplace.
     */
    @PostMapping("/{stationId}/session")
    public WorkplaceSessionView claim(@PathVariable UUID stationId,
                                      @RequestHeader(name = "X-Auth-User", required = false) String authUser,
                                      @RequestParam(name = "operator", required = false) String operator) {
        WorkplaceSession session = sessionService.claim(stationId, actor(authUser, operator));
        return WorkplaceSessionView.from(session, workplaceOf(stations.requireStation(stationId)));
    }

    /**
     * Heartbeat (keep-alive). Returns {@code {active:true}} while the session still owns the
     * workplace, or {@code {active:false, reason:"superseded"}} once it was taken over.
     */
    @PostMapping("/{stationId}/session/{sessionId}/heartbeat")
    public SessionStatusView heartbeat(@PathVariable UUID stationId, @PathVariable UUID sessionId) {
        return SessionStatusView.of(sessionService.heartbeat(stationId, sessionId));
    }

    /** Release the session (clean close). */
    @DeleteMapping("/{stationId}/session/{sessionId}")
    public SessionStatusView release(@PathVariable UUID stationId, @PathVariable UUID sessionId) {
        return SessionStatusView.of(sessionService.release(stationId, sessionId));
    }

    /**
     * Stream session status as Server-Sent Events: an immediate {@code status} event, then one each
     * time the session is heartbeated/changes, and a final {@code superseded} (or {@code released})
     * event when it is no longer active, after which the stream completes. A nicer alternative to
     * polling the heartbeat — the console can listen for the takeover push.
     */
    @GetMapping(path = "/{stationId}/session/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID stationId, @PathVariable UUID sessionId) {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; client closes it
        Thread poller = new Thread(() -> pollAndPush(stationId, sessionId, emitter), "wp-session-sse");
        poller.setDaemon(true);
        poller.start();
        return emitter;
    }

    private void pollAndPush(UUID stationId, UUID sessionId, SseEmitter emitter) {
        try {
            while (true) {
                WorkplaceSession session = sessionService.requireSession(stationId, sessionId);
                SessionStatusView status = SessionStatusView.of(session);
                emitter.send(SseEmitter.event().name(status.active() ? "status" : status.reason()).data(status));
                if (!status.active()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(2000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
        }
    }

    private WorkplaceView workplaceOf(GtpStation station) {
        Set<UUID> inUse = sessionService.activeStationIds(List.of(station.getId()));
        return WorkplaceView.from(station, stations.nodesOf(station.getId()), inUse.contains(station.getId()));
    }

    private static String actor(String forwarded, String fallback) {
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded;
        }
        return fallback == null || fallback.isBlank() ? "operator" : fallback;
    }
}
