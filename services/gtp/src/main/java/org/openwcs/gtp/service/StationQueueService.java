package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.domain.StationQueueEntry.Status;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationQueueEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The station inbound work queue (build.md §7). A transport routes a handling unit to a workplace;
 * while the emulator simulates the move the entry is IN_TRANSIT with a computed arrival time
 * (conveyor: distance / 0.5 m/s; ASRS / AMR / AutoStore: immediate). Once due it is QUEUED and the
 * operator works the queue in arrival (FIFO) order. Routing respects the per-station in-transit caps
 * (PICKING vs other) and skips stations that are draining (not accepting new work).
 */
@Service
public class StationQueueService {

    /** Conveyor transport speed used by the emulator to time arrivals. */
    private static final double CONVEYOR_MPS = 0.5;
    private static final List<String> ACTIVE = List.of(Status.IN_TRANSIT.name(), Status.QUEUED.name());

    private final GtpStationRepository stations;
    private final StationQueueEntryRepository queue;
    private final org.openwcs.gtp.repo.StationNodeRepository nodes;

    public StationQueueService(GtpStationRepository stations, StationQueueEntryRepository queue,
                               org.openwcs.gtp.repo.StationNodeRepository nodes) {
        this.stations = stations;
        this.queue = queue;
        this.nodes = nodes;
    }

    /** Inputs to route an HU to a station's queue. */
    public record EnqueueCommand(
            UUID huId, String huCode, UUID skuId, String skuCode, BigDecimal qty,
            String mode, String family, Double distanceM, UUID countTaskId, UUID countLineId) {
    }

    /** Thrown when a station cannot accept the routed HU; the controller maps it to 409. */
    public static class QueueRejectedException extends RuntimeException {
        public QueueRejectedException(String message) {
            super(message);
        }
    }

    @Transactional
    public StationQueueEntry enqueue(UUID stationId, EnqueueCommand cmd) {
        GtpStation station = station(stationId);
        OperatingMode mode = OperatingMode.parse(cmd.mode());
        if (!"ACTIVE".equals(station.getStatus())) {
            throw new QueueRejectedException("Station is not active.");
        }
        if (!station.isAcceptingWork()) {
            throw new QueueRejectedException("Station is deactivated (draining) and takes no new work.");
        }
        if (!station.supports(mode)) {
            throw new QueueRejectedException("Station does not support mode " + mode + ".");
        }
        boolean picking = mode == OperatingMode.PICKING;
        long inFlight = activeEntries(stationId).stream()
                .filter(e -> picking == OperatingMode.PICKING.name().equals(e.getMode()))
                .count();
        int cap = picking ? station.getMaxInTransitPicking() : station.getMaxInTransitOther();
        if (inFlight >= cap) {
            throw new QueueRejectedException("Station in-transit cap reached (" + cap + ").");
        }

        Instant now = Instant.now();
        // When the caller gives no transport timing, fall back to the station's STOCK node conveyor
        // distance projected from the automation topology (so the topology drives arrival timing).
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
        entry.setMode(mode.name());
        entry.setArrivalAt(arrival);
        entry.setCountTaskId(cmd.countTaskId());
        entry.setCountLineId(cmd.countLineId());
        entry.setStatus(arrival.isAfter(now) ? Status.IN_TRANSIT.name() : Status.QUEUED.name());
        return queue.save(entry);
    }

    /** The station's live queue: promotes any due IN_TRANSIT entries to QUEUED, then returns them in order. */
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
        return stations.save(station);
    }

    /**
     * An ACTIVE station in the warehouse that supports the mode, is accepting work, and has spare
     * in-transit capacity for that mode class. Used to route work (e.g. ASRS count totes) to a station.
     */
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

    /** The conveyor distance of the station's STOCK node, projected from topology, or null. */
    private Double stockNodeDistance(UUID stationId) {
        return nodes.findByStationIdAndRole(stationId, "STOCK").stream()
                .map(org.openwcs.gtp.domain.StationNode::getInboundDistanceM)
                .filter(java.util.Objects::nonNull)
                .map(java.math.BigDecimal::doubleValue)
                .findFirst()
                .orElse(null);
    }

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
