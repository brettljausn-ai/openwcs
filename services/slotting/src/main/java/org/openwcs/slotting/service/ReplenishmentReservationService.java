package org.openwcs.slotting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.slotting.api.ClaimNextResult;
import org.openwcs.slotting.client.MasterDataClient;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-operator reservation of replenishment (collection) tasks (Master Data Areas epic). An operator
 * working an area claims the next eligible PLANNED task whose destination pick face is inside that
 * area's subtree; the claim atomically reserves the task to them ({@code FOR UPDATE SKIP LOCKED}, so
 * concurrent operators get different tasks). A reservation is released on demand (logout / handheld
 * gone) or swept after a TTL if the operator never commits. The commit point is dispatch, which sets
 * {@code startedAt}; a started task is never released or swept.
 */
@Service
public class ReplenishmentReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentReservationService.class);

    private final ReplenishmentTaskRepository tasks;
    private final MasterDataClient masterData;
    private final Duration ttl;

    public ReplenishmentReservationService(ReplenishmentTaskRepository tasks, MasterDataClient masterData,
                                           @Value("${openwcs.slotting.reservation-ttl:15m}") Duration ttl) {
        this.tasks = tasks;
        this.masterData = masterData;
        this.ttl = ttl;
    }

    /**
     * Atomically reserve and return the next eligible collection task in an area for this operator,
     * or {@link ClaimNextResult#none()} if none is available. The area subtree's location ids are
     * resolved from master-data; the destination pick face must be one of them.
     */
    @Transactional
    public ClaimNextResult claimNext(UUID warehouseId, UUID areaId, String operator, String instanceId) {
        List<UUID> locationIds = masterData.areaLocationIds(areaId);
        if (locationIds.isEmpty()) {
            log.debug("claim-next: area {} resolved to no locations (warehouse {})", areaId, warehouseId);
            return ClaimNextResult.none();
        }
        Optional<ReplenishmentTask> next = tasks.lockNextClaimable(warehouseId, locationIds);
        if (next.isEmpty()) {
            return ClaimNextResult.none();
        }
        ReplenishmentTask task = next.get();
        task.setReservedBy(operator);
        task.setReservedAt(Instant.now());
        task.setReservedByInstance(instanceId);
        ReplenishmentTask saved = tasks.save(task);
        log.info("replenishment task {} reserved by {} (instance {}) for pick face {} in area {} (priority {})",
                saved.getId(), operator, instanceId, saved.getToLocationId(), areaId, saved.getPriority());
        return ClaimNextResult.of(saved);
    }

    /**
     * Free every uncommitted reservation (PLANNED, not yet started) held by a runtime instance.
     * Idempotent. Returns the number of tasks freed.
     */
    @Transactional
    public int release(String instanceId) {
        int freed = tasks.releaseByInstance(instanceId);
        if (freed > 0) {
            log.info("released {} replenishment reservation(s) held by instance {}", freed, instanceId);
        }
        return freed;
    }

    /**
     * Sweep stale reservations: free PLANNED, not-yet-started tasks reserved before {@code now - TTL}.
     * Returns the number freed.
     */
    @Transactional
    public int sweepStale() {
        Instant cutoff = Instant.now().minus(ttl);
        int freed = tasks.releaseStale(cutoff);
        if (freed > 0) {
            log.info("swept {} stale replenishment reservation(s) (reserved before {}, TTL {})", freed, cutoff, ttl);
        }
        return freed;
    }
}
