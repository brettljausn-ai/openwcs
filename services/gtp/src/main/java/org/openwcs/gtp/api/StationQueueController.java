package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.StationQueueService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The station inbound work queue and the deactivate/drain switch (build.md §7). A transport routes
 * an HU to a station via {@code POST /stations/{id}/queue}; the operator reads the live queue and
 * completes the head. Deactivating a station drains it: it finishes queued work but takes no new HUs.
 */
@RestController
@RequestMapping("/api/gtp")
public class StationQueueController {

    private final StationQueueService queue;
    private final GtpStationService stations;

    public StationQueueController(StationQueueService queue, GtpStationService stations) {
        this.queue = queue;
        this.stations = stations;
    }

    @PostMapping("/stations/{stationId}/queue")
    public StationQueueEntryView enqueue(@PathVariable UUID stationId, @RequestBody EnqueueRequest req) {
        return StationQueueEntryView.from(queue.enqueue(stationId, new StationQueueService.EnqueueCommand(
                req.huId(), req.huCode(), req.skuId(), req.skuCode(), req.qty(),
                req.mode(), req.family(), req.distanceM(), req.countTaskId(), req.countLineId())));
    }

    @GetMapping("/stations/{stationId}/queue")
    public List<StationQueueEntryView> queue(@PathVariable UUID stationId) {
        return queue.queue(stationId).stream().map(StationQueueEntryView::from).toList();
    }

    @PostMapping("/queue/{entryId}/complete")
    public StationQueueEntryView complete(@PathVariable UUID entryId) {
        return StationQueueEntryView.from(queue.complete(entryId));
    }

    /** Deactivate (drain) a station: finishes queued work, accepts no new inbound HUs. */
    @PostMapping("/stations/{stationId}/deactivate")
    public StationView deactivate(@PathVariable UUID stationId) {
        var s = queue.setAccepting(stationId, false);
        return StationView.from(s, stations.nodesOf(stationId));
    }

    /** Re-activate a drained station so it accepts new inbound HUs again. */
    @PostMapping("/stations/{stationId}/activate")
    public StationView activate(@PathVariable UUID stationId) {
        var s = queue.setAccepting(stationId, true);
        return StationView.from(s, stations.nodesOf(stationId));
    }

    /** Request to route an HU to a station's queue. */
    public record EnqueueRequest(
            UUID huId, String huCode, UUID skuId, String skuCode, BigDecimal qty,
            @NotBlank String mode, String family, Double distanceM, UUID countTaskId, UUID countLineId) {
    }

    /** A queued/in-transit HU at a station. */
    public record StationQueueEntryView(
            UUID id, UUID stationId, UUID huId, String huCode, UUID skuId, String skuCode,
            BigDecimal qty, String mode, String status, Instant arrivalAt,
            UUID countTaskId, UUID countLineId) {

        public static StationQueueEntryView from(StationQueueEntry e) {
            return new StationQueueEntryView(e.getId(), e.getStationId(), e.getHuId(), e.getHuCode(),
                    e.getSkuId(), e.getSkuCode(), e.getQty(), e.getMode(), e.getStatus(), e.getArrivalAt(),
                    e.getCountTaskId(), e.getCountLineId());
        }
    }
}
