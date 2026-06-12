package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.gtp.client.FlowInductionClient;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.domain.StationQueueEntry.Status;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods-to-person station services. Since ADR-0007 Phase 3c-1 the inbound presentation queue
 * ({@code REQUESTED → IN_TRANSIT → QUEUED → DONE}, with the per-station in-transit cap) is owned by
 * <strong>flow-orchestrator</strong>, not gtp: the workstation screen reads the slice from flow (via
 * {@link FlowInductionClient}) and operator completion fans the entry out to flow's DONE endpoint.
 *
 * <p>The return-to-storage decision is owned by flow too: marking the entry DONE starts flow's
 * return leg, where ONLY slotting decides the destination. gtp dispatches no store-back transport
 * of its own anymore (the old best-effort {@code storeBack} double-dispatched against flow's
 * return leg and is removed).
 *
 * <p>The legacy gtp-owned inbound queue ({@link #enqueue}, {@link #queue}, {@link #complete}, the
 * {@code CONVEYOR_MPS} arrival computation and the local in-transit cap) is {@link Deprecated} and no
 * longer the source of truth; counting stopped calling the inbound enqueue. It is kept physically
 * present (table + code) for 3c-1; its destructive removal is 3c-2 (out of scope).
 */
@Service
public class StationQueueService {

    private static final Logger log = LoggerFactory.getLogger(StationQueueService.class);

    /**
     * Conveyor transport speed once used by gtp to time arrivals.
     *
     * @deprecated arrival timing is now driven by flow's CONVEY device-task callback (ADR-0007 §4.3);
     *     this constant is dead.
     */
    @Deprecated
    private static final double CONVEYOR_MPS = 0.5;
    private static final List<String> ACTIVE = List.of(Status.IN_TRANSIT.name(), Status.QUEUED.name());

    private final GtpStationRepository stations;
    private final StationQueueEntryRepository queue;
    private final org.openwcs.gtp.repo.StationNodeRepository nodes;
    private final FlowInductionClient induction;

    public StationQueueService(GtpStationRepository stations, StationQueueEntryRepository queue,
                               org.openwcs.gtp.repo.StationNodeRepository nodes,
                               FlowInductionClient induction) {
        this.stations = stations;
        this.queue = queue;
        this.nodes = nodes;
        this.induction = induction;
    }

    /**
     * Inputs to route an HU to a station's queue.
     *
     * @deprecated the inbound queue moved to flow (ADR-0007 §1); request presentation via flow's
     *     {@code POST /api/flow/induction/requests} instead. Kept for the legacy gtp enqueue path.
     */
    @Deprecated
    public record EnqueueCommand(
            UUID huId, String huCode, UUID skuId, String skuCode, BigDecimal qty,
            String mode, String family, Double distanceM, UUID countTaskId, UUID countLineId,
            UUID locationId) {
    }

    /**
     * Thrown when a station cannot accept the routed HU; the controller maps it to 409.
     *
     * @deprecated inbound rejection is gone — flow's REQUESTED stage is uncapped (ADR-0007 §3.1).
     *     Retained as the not-found signal for the legacy/local code paths.
     */
    @Deprecated
    public static class QueueRejectedException extends RuntimeException {
        public QueueRejectedException(String message) {
            super(message);
        }
    }

    /**
     * Read a workplace's inbound queue slice from flow (ADR-0007 §3.2): the live
     * {@code REQUESTED, IN_TRANSIT, QUEUED} pipeline, DONE excluded, ordered by flow. This is the feed
     * the workstation screen renders; gtp no longer owns it.
     */
    @Transactional(readOnly = true)
    public List<FlowInductionClient.InductionEntry> inductionQueue(UUID workplaceId) {
        return induction.readQueue(workplaceId);
    }

    /**
     * Operator completion of a presented tote (ADR-0007 §6.2): mark the flow induction entry DONE.
     * Flow owns the return leg from here — only slotting decides where the tote goes, and gtp
     * dispatches NO transport of its own (the old parallel store-back double-dispatched against
     * flow's return leg). Returns the DONE flow entry.
     */
    @Transactional
    public FlowInductionClient.InductionEntry completeInduction(UUID entryId) {
        FlowInductionClient.InductionEntry done = induction.markDone(entryId);
        if (done == null) {
            throw new QueueRejectedException("Induction entry not found.");
        }
        log.info("induction entry {} completed at workplace {}: tote {} (sku {}, mode {}) marked DONE; "
                        + "flow owns the return leg (slotting decides the destination)",
                entryId, done.workplaceId(), done.huCode(), done.skuCode(), done.mode());
        return done;
    }

    // ---------------------------------------------------------------------------------------------
    // Legacy gtp-owned inbound queue (ADR-0007 §1: relocated to flow). Deprecated, not the source of
    // truth, kept present for 3c-1; destructive removal is 3c-2.
    // ---------------------------------------------------------------------------------------------

    /** @deprecated inbound queue moved to flow; request presentation via flow (ADR-0007 §3.1). */
    @Deprecated
    @Transactional
    public StationQueueEntry enqueue(UUID stationId, EnqueueCommand cmd) {
        GtpStation station = station(stationId);
        OperatingMode mode = OperatingMode.parse(cmd.mode());
        if (!"ACTIVE".equals(station.getStatus())) {
            log.warn("enqueue rejected at station {}: station status is {} (hu {}, sku {}, mode {})",
                    station.getCode(), station.getStatus(), cmd.huCode(), cmd.skuCode(), mode);
            throw new QueueRejectedException("Station is not active.");
        }
        if (!station.isAcceptingWork()) {
            log.warn("enqueue rejected at station {}: station is draining and takes no new work "
                    + "(hu {}, sku {}, mode {})", station.getCode(), cmd.huCode(), cmd.skuCode(), mode);
            throw new QueueRejectedException("Station is deactivated (draining) and takes no new work.");
        }
        if (!station.supports(mode)) {
            log.warn("enqueue rejected at station {}: mode {} not supported (supports {}) (hu {}, sku {})",
                    station.getCode(), mode, station.getSupportedModes(), cmd.huCode(), cmd.skuCode());
            throw new QueueRejectedException("Station does not support mode " + mode + ".");
        }
        boolean picking = mode == OperatingMode.PICKING;
        long inFlight = activeEntries(stationId).stream()
                .filter(e -> picking == OperatingMode.PICKING.name().equals(e.getMode()))
                .count();
        int cap = picking ? station.getMaxInTransitPicking() : station.getMaxInTransitOther();
        if (inFlight >= cap) {
            log.warn("enqueue rejected at station {}: {} in-transit cap reached ({} of {} in flight); "
                            + "hu {} (sku {}) is not queued",
                    station.getCode(), picking ? "PICKING" : "OTHER", inFlight, cap,
                    cmd.huCode(), cmd.skuCode());
            throw new QueueRejectedException("Station in-transit cap reached (" + cap + ").");
        }

        Instant now = Instant.now();
        String family = cmd.family();
        Double distanceM = cmd.distanceM();
        if (family == null && distanceM == null) {
            Double stockDistance = stockNodeDistance(stationId);
            if (stockDistance != null) {
                family = "CONVEYOR";
                distanceM = stockDistance;
            }
        }
        Instant arrival = arrivalAt(now, family, distanceM);
        StationQueueEntry entry = new StationQueueEntry();
        entry.setStationId(stationId);
        entry.setWarehouseId(station.getWarehouseId());
        entry.setHuId(cmd.huId());
        entry.setHuCode(cmd.huCode());
        entry.setSkuId(cmd.skuId());
        entry.setSkuCode(cmd.skuCode());
        entry.setQty(cmd.qty());
        entry.setLocationId(cmd.locationId());
        entry.setMode(mode.name());
        entry.setArrivalAt(arrival);
        entry.setCountTaskId(cmd.countTaskId());
        entry.setCountLineId(cmd.countLineId());
        entry.setStatus(arrival.isAfter(now) ? Status.IN_TRANSIT.name() : Status.QUEUED.name());
        return queue.save(entry);
    }

    /** @deprecated the live queue is read from flow via {@link #inductionQueue(UUID)}. */
    @Deprecated
    @Transactional
    public List<StationQueueEntry> queue(UUID stationId) {
        Instant now = Instant.now();
        List<StationQueueEntry> active = activeEntries(stationId);
        for (StationQueueEntry e : active) {
            if (Status.IN_TRANSIT.name().equals(e.getStatus()) && !e.getArrivalAt().isAfter(now)) {
                e.setStatus(Status.QUEUED.name());
                queue.save(e);
            }
        }
        return active;
    }

    /** @deprecated completion now fans out to flow via {@link #completeInduction(UUID)}. */
    @Deprecated
    @Transactional
    public StationQueueEntry complete(UUID entryId) {
        StationQueueEntry e = queue.findById(entryId)
                .orElseThrow(() -> new QueueRejectedException("Queue entry not found."));
        e.setStatus(Status.DONE.name());
        return queue.save(e);
    }

    @Transactional
    public GtpStation setAccepting(UUID stationId, boolean accepting) {
        GtpStation station = station(stationId);
        station.setAcceptingWork(accepting);
        if (accepting) {
            log.info("station {} reactivated: accepting new inbound work again", station.getCode());
        } else {
            log.info("station {} deactivated (draining): finishes work already queued, "
                    + "accepts no new inbound units", station.getCode());
        }
        return stations.save(station);
    }

    /**
     * An ACTIVE station in the warehouse that supports the mode, is accepting work, and has spare
     * in-transit capacity for that mode class. Used to route work (e.g. ASRS count totes) to a station.
     *
     * @deprecated the cap now lives in flow (ADR-0007 §4.1, metered at RETRIEVE dispatch). Kept for
     *     callers still selecting a destination workplace, but the local capacity gate is advisory.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public Optional<GtpStation> findRoutableStation(UUID warehouseId, OperatingMode mode) {
        boolean picking = mode == OperatingMode.PICKING;
        return stations.findByWarehouseId(warehouseId).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus()) && s.isAcceptingWork() && s.supports(mode))
                .filter(s -> {
                    long inFlight = activeEntries(s.getId()).stream()
                            .filter(e -> picking == OperatingMode.PICKING.name().equals(e.getMode()))
                            .count();
                    return inFlight < (picking ? s.getMaxInTransitPicking() : s.getMaxInTransitOther());
                })
                .findFirst();
    }

    private List<StationQueueEntry> activeEntries(UUID stationId) {
        return queue.findByStationIdAndStatusInOrderByArrivalAtAsc(stationId, ACTIVE);
    }

    /**
     * The conveyor distance of the station's STOCK node, projected from topology, or null.
     *
     * @deprecated arrival timing moved to flow's CONVEY callback (ADR-0007 §4.3).
     */
    @Deprecated
    private Double stockNodeDistance(UUID stationId) {
        return nodes.findByStationIdAndRole(stationId, "STOCK").stream()
                .map(org.openwcs.gtp.domain.StationNode::getInboundDistanceM)
                .filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue)
                .findFirst()
                .orElse(null);
    }

    /** @deprecated arrival timing moved to flow's CONVEY callback (ADR-0007 §4.3). */
    @Deprecated
    private static Instant arrivalAt(Instant now, String family, Double distanceM) {
        // Conveyors travel at CONVEYOR_MPS; ASRS / AMR / AutoStore deliver immediately.
        if ("CONVEYOR".equalsIgnoreCase(family) && distanceM != null && distanceM > 0) {
            long millis = (long) (distanceM / CONVEYOR_MPS * 1000);
            return now.plusMillis(millis);
        }
        return now;
    }

    private GtpStation station(UUID stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new QueueRejectedException("Station not found."));
    }
}
