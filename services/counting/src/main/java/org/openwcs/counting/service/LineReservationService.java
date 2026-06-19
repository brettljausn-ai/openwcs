package org.openwcs.counting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.domain.CountLine;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-operator reservation of count lines for area-driven counting. claim-next atomically reserves
 * the next eligible line in a Master Data Area subtree (PENDING, unreserved, location in the area)
 * using {@code FOR UPDATE SKIP LOCKED} so concurrent operators never grab the same line; release
 * frees an instance's not-yet-started reservations; and the sweeper (see {@link ReservationSweeper})
 * reclaims stale reservations whose operator never started counting.
 */
@Service
public class LineReservationService {

    private static final Logger log = LoggerFactory.getLogger(LineReservationService.class);

    private final CountLineRepository lines;
    private final CountTaskRepository tasks;
    private final MasterDataClient masterData;
    private final Duration reservationTtl;

    public LineReservationService(CountLineRepository lines,
                                  CountTaskRepository tasks,
                                  MasterDataClient masterData,
                                  @Value("${openwcs.counting.reservation-ttl:15m}") Duration reservationTtl) {
        this.lines = lines;
        this.tasks = tasks;
        this.masterData = masterData;
        this.reservationTtl = reservationTtl;
    }

    /**
     * Reserve the next eligible count line in the area subtree for {@code operator}, held by
     * {@code instanceId}. Resolves the area's location ids via master-data, then atomically locks and
     * stamps one candidate. Returns {@link ClaimNextResult#none()} when the area is empty or nothing
     * is eligible.
     */
    @Transactional
    public ClaimNextResult claimNext(UUID warehouseId, UUID areaId, String instanceId, String operator) {
        if (warehouseId == null) {
            throw new IllegalArgumentException("warehouseId is required");
        }
        if (areaId == null) {
            throw new IllegalArgumentException("areaId is required");
        }
        List<UUID> locationIds = masterData.areaLocationIds(areaId);
        if (locationIds.isEmpty()) {
            log.debug("claim-next: area {} resolved to no locations, nothing to claim", areaId);
            return ClaimNextResult.none();
        }
        UUID lineId = lines.lockNextClaimable(warehouseId, locationIds).orElse(null);
        if (lineId == null) {
            return ClaimNextResult.none();
        }
        // lockNextClaimable holds a row lock on this id for the rest of the transaction, so this load
        // and the stamp below cannot race another claimer.
        CountLine line = lines.findById(lineId).orElseThrow();
        line.setReservedBy(operator);
        line.setReservedAt(Instant.now());
        line.setReservedByInstance(instanceId);

        CountTask task = tasks.findById(line.getCountTaskId()).orElseThrow();
        log.info("claim-next: operator {} (instance {}) reserved line {} (task {}, location {}) in area {}",
                operator, instanceId, lineId, line.getCountTaskId(), line.getLocationId(), areaId);
        return new ClaimNextResult(true, line.getCountTaskId(), line.getId(), line.getLocationId(),
                line.getSkuId(), line.getExpectedQty(), line.getUomCode(), task.getCountType());
    }

    /**
     * Free every not-yet-started reservation held by {@code instanceId} (handheld app closed / logged
     * out). Started lines are left reserved (the operator is mid-count). Idempotent. Returns the
     * number of lines freed.
     */
    @Transactional
    public int release(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        int freed = lines.releaseByInstance(instanceId);
        log.info("release: freed {} unstarted reservation(s) held by instance {}", freed, instanceId);
        return freed;
    }

    /**
     * Reclaim stale reservations: reserved, not started, and older than the TTL. Returns how many were
     * freed. Driven by {@link ReservationSweeper}.
     */
    @Transactional
    public int sweepStale() {
        Instant cutoff = Instant.now().minus(reservationTtl);
        int freed = lines.releaseStale(cutoff);
        if (freed > 0) {
            log.info("reservation sweep: reclaimed {} stale unstarted reservation(s) older than {}",
                    freed, reservationTtl);
        }
        return freed;
    }
}
