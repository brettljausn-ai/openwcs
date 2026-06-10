package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.openwcs.counting.domain.CountLine;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes count cells whose stock lives in an ASRS-family storage block to a goods-to-person counting
 * station, and persists the outcome (status + reason) on the task so the background retry sweep can
 * re-attempt a failed routing and the UI can show why a task did or did not route.
 *
 * <p>When the hardware emulator is ON and an ACTIVE station accepts {@code STOCK_COUNT} work, each
 * qualifying cell's handling unit (tote) is requested for presentation at the station via flow's
 * inbound induction request (ADR-0007 Phase 3c-1). Flow now owns the induction queue and orchestrates
 * the retrieve + convey journey itself (so the move shows up on the Transport screen); counting no
 * longer enqueues to gtp nor dispatches the RETRIEVE device task.
 *
 * <p>Idempotent: only lines whose {@code routed} flag is false are routed, and each is marked routed
 * on success, so a retry never creates a duplicate induction request. {@link #routeTask} never throws
 * — a hard failure still records status FAILED so the scheduler / generate path is never broken.
 */
@Service
public class CountRoutingService {

    private static final Logger log = LoggerFactory.getLogger(CountRoutingService.class);
    private static final String MODE = "STOCK_COUNT";
    /** Destination workplace kind for the flow induction request (today a GTP station). */
    private static final String WORKPLACE_KIND = "GTP_STATION";
    /** Fallback device family if a routed cell's storage type can't be mapped (shouldn't happen). */
    private static final String DEFAULT_FAMILY = "ASRS";
    private static final int REASON_MAX = 140;

    static final String PENDING = "PENDING";
    static final String ROUTED = "ROUTED";
    static final String NOT_REQUIRED = "NOT_REQUIRED";
    static final String FAILED = "FAILED";

    private final CountTaskRepository tasks;
    private final CountLineRepository lines;
    private final MasterDataClient masterData;
    private final InventoryClient inventory;
    private final GtpClient gtp;
    private final FlowClient flow;

    public CountRoutingService(CountTaskRepository tasks, CountLineRepository lines,
                               MasterDataClient masterData, InventoryClient inventory,
                               GtpClient gtp, FlowClient flow) {
        this.tasks = tasks;
        this.lines = lines;
        this.masterData = masterData;
        this.inventory = inventory;
        this.gtp = gtp;
        this.flow = flow;
    }

    /**
     * Compute and persist the ASRS routing outcome for a single task. Idempotent and never throws:
     * already-routed lines are skipped, and any I/O blow-up is captured as a FAILED status instead of
     * propagating (so the retry scheduler and count-task creation are never broken).
     *
     * <p>Deliberately NOT {@code @Transactional}: each line's {@code routed} flag is saved in its own
     * transaction the moment its tote is routed, so a later line failing (or a downstream error) can
     * never roll back an already-routed line and cause a duplicate transport on the next retry. The
     * transport/enqueue calls are non-transactional HTTP side effects.
     */
    public void routeTask(CountTask task) {
        String status;
        String reason;
        try {
            Outcome outcome = computeOutcome(task);
            status = outcome.status();
            reason = outcome.reason();
        } catch (Throwable t) {
            // Defensive: a hard failure (a client/repo blowing up) records FAILED rather than escaping.
            status = FAILED;
            reason = trim("Routing failed: " + t.getMessage());
            log.warn("count task {} routing hit a hard failure: {}", task.getId(), t.toString());
        }

        task.setRoutingStatus(status);
        task.setRoutingReason(reason);
        task.setRoutingAttempts(task.getRoutingAttempts() + 1);
        task.setRoutingAttemptAt(Instant.now());
        tasks.save(task);
        log.info("count task {} routing -> {} ({})", task.getId(), status, reason);
    }

    private Outcome computeOutcome(CountTask task) {
        List<CountLine> taskLines = lines.findByCountTaskId(task.getId());
        if (taskLines.isEmpty()) {
            return new Outcome(NOT_REQUIRED, "No cells to route.");
        }
        if (!masterData.emulatorEnabled()) {
            return new Outcome(FAILED, "Hardware emulator is off.");
        }
        Optional<UUID> station = gtp.findActiveCountingStation(task.getWarehouseId());
        if (station.isEmpty()) {
            return new Outcome(FAILED, "No active STOCK_COUNT station.");
        }
        UUID stationId = station.get();

        int asrsLines = 0;     // ASRS-family lines seen (whether or not they had an HU)
        int routedNow = 0;     // lines newly routed this pass
        int asrsRouted = 0;    // ASRS-family lines that are now routed (this pass or a prior one)
        String firstError = null;

        for (CountLine line : taskLines) {
            String storageType =
                    masterData.storageTypeOfLocation(task.getWarehouseId(), line.getLocationId()).orElse(null);
            if (!MasterDataClient.isAsrsFamily(storageType)) {
                continue; // not an automated system; the operator counts it in place.
            }
            asrsLines++;
            if (line.isRouted()) {
                asrsRouted++;
                continue; // already routed on a prior attempt — never route twice.
            }
            // Dispatch to the adapter family that actually services this storage (shuttle/crane ->
            // ASRS, AutoStore -> AUTOSTORE, AMR-GTP -> AMR), not a hardcoded ASRS.
            String family = familyOrDefault(storageType);
            try {
                boolean routed = routeLine(task.getWarehouseId(), stationId, line, family);
                if (routed) {
                    line.setRouted(true);
                    lines.save(line);
                    routedNow++;
                    asrsRouted++;
                }
            } catch (Throwable e) {
                log.warn("could not route count cell (location {}, sku {}) to station {}: {}",
                        line.getLocationId(), line.getSkuId(), stationId, e.toString());
                if (firstError == null) {
                    firstError = e.getMessage() == null ? e.toString() : e.getMessage();
                }
            }
        }

        if (firstError != null) {
            return new Outcome(FAILED, trim("Transport request failed: " + firstError));
        }
        if (asrsLines == 0) {
            return new Outcome(NOT_REQUIRED, "No ASRS-stored stock to route.");
        }
        if (asrsRouted == asrsLines) {
            return new Outcome(ROUTED, "Routed " + asrsRouted + " tote(s) to the counting station.");
        }
        // ASRS cells exist but none had a handling unit to move.
        return new Outcome(NOT_REQUIRED, "ASRS cells have no handling unit to move.");
    }

    /**
     * Route one ASRS-family line's tote to the station. Returns true when a tote was actually
     * requested for presentation, false when there is no handling unit at the cell (nothing to
     * route).
     *
     * <p>ADR-0007 Phase 3c-1: the inbound induction queue relocated to flow-orchestrator. Counting
     * issues a single {@code POST /api/flow/induction/requests} via
     * {@link FlowClient#requestPresentation}; flow now owns the queue and orchestrates the
     * retrieve + convey journey itself (counting no longer enqueues to gtp nor dispatches the
     * RETRIEVE device task). The capacity gate moved to flow — {@code REQUESTED} is uncapped and flow
     * meters retrievals — so the request always succeeds and routing reduces to "an HU exists".
     */
    private boolean routeLine(UUID warehouseId, UUID stationId, CountLine line, String family) {
        Optional<InventoryClient.HandlingUnit> hu =
                inventory.findHuAt(warehouseId, line.getSkuId(), line.getLocationId());
        if (hu.isEmpty()) {
            return false; // no tote to move.
        }
        InventoryClient.HandlingUnit tote = hu.get();
        String skuCode = masterData.skuCode(line.getSkuId()).orElse(null);
        BigDecimal qty = tote.qty();

        // Single call to flow's induction request: flow creates a REQUESTED entry and orchestrates
        // RETRIEVE then CONVEY itself. The destination workplace is the GTP station; the device
        // family (Phase-1 deviceFamilyOf resolution) tells flow which adapter to retrieve from.
        UUID entryId = flow.requestPresentation(new FlowClient.InductionRequest(
                warehouseId, stationId, WORKPLACE_KIND, tote.huId(), tote.huCode(),
                line.getSkuId(), skuCode, qty, line.getLocationId(), MODE, family,
                line.getCountTaskId(), line.getId()));

        log.info("requested presentation of count tote {} (sku {}) at GTP station {} for stock "
                + "count; induction entry {}", tote.huCode(), skuCode, stationId, entryId);
        return true;
    }

    /** Resolve the device family for a routed cell's storage type, falling back to ASRS. */
    private static String familyOrDefault(String storageType) {
        String family = MasterDataClient.deviceFamilyOf(storageType);
        return family != null ? family : DEFAULT_FAMILY;
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= REASON_MAX ? s : s.substring(0, REASON_MAX);
    }

    private record Outcome(String status, String reason) {
    }
}
