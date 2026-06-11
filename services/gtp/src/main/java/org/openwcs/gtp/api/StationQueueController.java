package org.openwcs.gtp.api;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.gtp.client.FlowInductionClient;
import org.openwcs.gtp.domain.MaintenanceOrder;
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
 * The station inbound work queue feed and the deactivate/drain switch (build.md §7, ADR-0007 §6.2).
 *
 * <p>Since 3c-1 the inbound presentation queue is owned by <strong>flow-orchestrator</strong>: the
 * workstation screen's queue feed ({@code GET /stations/{id}/queue}) reads flow's induction slice,
 * and operator completion ({@code POST /queue/{id}/complete}) marks the flow entry DONE then runs
 * gtp's store-back. The inbound enqueue ({@code POST /stations/{id}/queue}) is deprecated: counting
 * no longer calls it (it requests presentation from flow instead).
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

    /**
     * Legacy inbound enqueue. Deprecated since 3c-1 (ADR-0007 §6): the inbound queue moved to flow and
     * counting requests presentation via {@code POST /api/flow/induction/requests} instead. Kept as a
     * no-longer-called compatibility shim; do not route new work through it.
     */
    @Deprecated
    @PostMapping("/stations/{stationId}/queue")
    public StationQueueEntryView enqueue(@PathVariable UUID stationId, @RequestBody EnqueueRequest req) {
        return StationQueueEntryView.from(queue.enqueue(stationId, new StationQueueService.EnqueueCommand(
                req.huId(), req.huCode(), req.skuId(), req.skuCode(), req.qty(),
                req.mode(), req.family(), req.distanceM(), req.countTaskId(), req.countLineId(),
                req.locationId())));
    }

    /**
     * The workstation screen's inbound queue feed: the flow-owned induction slice
     * ({@code REQUESTED, IN_TRANSIT, QUEUED}; DONE excluded) for this station as a workplace.
     */
    @GetMapping("/stations/{stationId}/queue")
    public List<StationQueueEntryView> queue(@PathVariable UUID stationId) {
        return queue.inductionQueue(stationId).stream().map(StationQueueEntryView::from).toList();
    }

    /**
     * Operator completion of the head tote: mark the flow induction entry DONE, then store back
     * (ADR-0007 §6.2). The path variable is the flow induction entry id.
     */
    @PostMapping("/queue/{entryId}/complete")
    public StationQueueEntryView complete(@PathVariable UUID entryId) {
        return StationQueueEntryView.from(queue.completeInduction(entryId));
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
     * Dirty-tote exception: pull the tote into a CLEANING maintenance order and mark its flow
     * induction entry DONE WITHOUT a store-back (it goes to maintenance, not back to stock).
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
    public Map<String, Object> broken(@PathVariable UUID stationId, @RequestBody BrokenRequest req,
                                      @org.springframework.web.bind.annotation.RequestHeader(
                                              name = "X-Auth-User", required = false) String authUser) {
        BigDecimal adjusted = exceptions.markBroken(req.queueEntryId(), req.qty(), authUser);
        return Map.of("adjusted", adjusted);
    }

    /** Body for the dirty-tote exception. The id is the flow induction entry id. */
    public record ExceptionRequest(UUID queueEntryId) {
    }

    /** Body for the broken-product exception. The id is the flow induction entry id. */
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

    /**
     * Request to route an HU to a station's queue.
     *
     * @deprecated the inbound queue moved to flow; request presentation via flow (ADR-0007 §6).
     */
    @Deprecated
    public record EnqueueRequest(
            UUID huId, String huCode, UUID skuId, String skuCode, BigDecimal qty,
            @NotBlank String mode, String family, Double distanceM, UUID countTaskId, UUID countLineId,
            UUID locationId) {
    }

    /**
     * A workstation-screen queue row, sourced from a flow induction entry. {@code status} is the flow
     * lifecycle stage ({@code REQUESTED|IN_TRANSIT|QUEUED}); {@code arrivalAt} carries the actual
     * arrival ({@code queuedAt}, set at QUEUED); {@code arrivalSeq} is the per-workplace arrival order
     * (null until QUEUED).
     */
    public record StationQueueEntryView(
            UUID id, UUID stationId, UUID huId, String huCode, UUID skuId, String skuCode,
            BigDecimal qty, String mode, String status, Instant arrivalAt, Long arrivalSeq,
            UUID countTaskId, UUID countLineId, UUID locationId) {

        /** Map a flow induction entry to the workstation-screen row. */
        public static StationQueueEntryView from(FlowInductionClient.InductionEntry e) {
            return new StationQueueEntryView(e.id(), e.workplaceId(), e.huId(), e.huCode(),
                    e.skuId(), e.skuCode(), e.qty(), e.mode(), e.status(), e.queuedAt(), e.arrivalSeq(),
                    e.countTaskId(), e.countLineId(), e.locationId());
        }

        /** Map a legacy local gtp queue entry (deprecated enqueue path only). */
        public static StationQueueEntryView from(org.openwcs.gtp.domain.StationQueueEntry e) {
            return new StationQueueEntryView(e.getId(), e.getStationId(), e.getHuId(), e.getHuCode(),
                    e.getSkuId(), e.getSkuCode(), e.getQty(), e.getMode(), e.getStatus(), e.getArrivalAt(),
                    null, e.getCountTaskId(), e.getCountLineId(), e.getLocationId());
        }
    }
}
