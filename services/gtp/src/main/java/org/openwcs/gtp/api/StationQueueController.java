package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.service.GtpStationService;
import org.openwcs.gtp.service.StationExceptionService;
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
    private final StationExceptionService exceptions;

    public StationQueueController(StationQueueService queue, GtpStationService stations,
                                  StationExceptionService exceptions) {
        this.queue = queue;
        this.stations = stations;
        this.exceptions = exceptions;
    }

    @PostMapping("/stations/{stationId}/queue")
    public StationQueueEntryView enqueue(@PathVariable UUID stationId, @RequestBody EnqueueRequest req) {
        return StationQueueEntryView.from(queue.enqueue(stationId, new StationQueueService.EnqueueCommand(
                req.huId(), req.huCode(), req.skuId(), req.skuCode(), req.qty(),
                req.mode(), req.family(), req.distanceM(), req.countTaskId(), req.countLineId(),
                req.locationId())));
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

    /**
     * Dirty-tote exception: pull the tote into a CLEANING maintenance order and complete its queue
     * entry WITHOUT a store-back (it goes to maintenance, not back to stock).
     */
    @PostMapping("/stations/{stationId}/exceptions/dirty-tote")
    public MaintenanceOrderView dirtyTote(@PathVariable UUID stationId, @RequestBody ExceptionRequest req) {
        return MaintenanceOrderView.from(exceptions.markDirty(stationId, req.queueEntryId()));
    }

    /**
     * Broken-product exception: write off {@code qty} units of the tote's SKU (negative DAMAGED stock
     * adjustment). The tote stays in the queue so the operator keeps working it.
     */
    @PostMapping("/stations/{stationId}/exceptions/broken")
    public Map<String, Object> broken(@PathVariable UUID stationId, @RequestBody BrokenRequest req) {
        BigDecimal adjusted = exceptions.markBroken(req.queueEntryId(), req.qty());
        return Map.of("adjusted", adjusted);
    }

    /** Body for the dirty-tote exception. */
    public record ExceptionRequest(UUID queueEntryId) {
    }

    /** Body for the broken-product exception. */
    public record BrokenRequest(UUID queueEntryId, BigDecimal qty) {
    }

    /** A maintenance order opened from an operator exception. */
    public record MaintenanceOrderView(
            UUID id, UUID warehouseId, UUID huId, String huCode, UUID stationId, UUID skuId,
            String skuCode, String reason, String status) {

        public static MaintenanceOrderView from(MaintenanceOrder o) {
            return new MaintenanceOrderView(o.getId(), o.getWarehouseId(), o.getHuId(), o.getHuCode(),
                    o.getStationId(), o.getSkuId(), o.getSkuCode(), o.getReason(), o.getStatus());
        }
    }

    /** Request to route an HU to a station's queue. */
    public record EnqueueRequest(
            UUID huId, String huCode, UUID skuId, String skuCode, BigDecimal qty,
            @NotBlank String mode, String family, Double distanceM, UUID countTaskId, UUID countLineId,
            UUID locationId) {
    }

    /** A queued/in-transit HU at a station. */
    public record StationQueueEntryView(
            UUID id, UUID stationId, UUID huId, String huCode, UUID skuId, String skuCode,
            BigDecimal qty, String mode, String status, Instant arrivalAt,
            UUID countTaskId, UUID countLineId, UUID locationId) {

        public static StationQueueEntryView from(StationQueueEntry e) {
            return new StationQueueEntryView(e.getId(), e.getStationId(), e.getHuId(), e.getHuCode(),
                    e.getSkuId(), e.getSkuCode(), e.getQty(), e.getMode(), e.getStatus(), e.getArrivalAt(),
                    e.getCountTaskId(), e.getCountLineId(), e.getLocationId());
        }
    }
}
