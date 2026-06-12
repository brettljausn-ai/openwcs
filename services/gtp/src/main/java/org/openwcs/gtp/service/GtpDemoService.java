package org.openwcs.gtp.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.api.DemoClearResult;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.PutInstructionRepository;
import org.openwcs.gtp.repo.StationQueueEntryRepository;
import org.openwcs.gtp.repo.TaskLineRepository;
import org.openwcs.gtp.repo.WorkCycleRepository;
import org.openwcs.gtp.repo.WorkplaceSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode for goods-to-person (build.md §4.8). A full operational reset for a warehouse:
 * purge ALL transactional GTP state — work cycles and their put instructions / task lines,
 * workplace sessions, and open destination demand — while leaving the station/node configuration
 * (GtpStation, StationNode) intact. Invoked when demo mode is turned off.
 *
 * <p>The operational entities do not carry a warehouse id directly; they hang off the warehouse's
 * stations. We resolve the warehouse's stations, then delete everything bound to them.
 */
@Service
public class GtpDemoService {

    private final GtpStationRepository stations;
    private final WorkCycleRepository workCycles;
    private final PutInstructionRepository putInstructions;
    private final TaskLineRepository taskLines;
    private final WorkplaceSessionRepository sessions;
    private final DestinationDemandRepository demands;
    private final StationQueueEntryRepository queue;

    public GtpDemoService(GtpStationRepository stations,
                          WorkCycleRepository workCycles, PutInstructionRepository putInstructions,
                          TaskLineRepository taskLines, WorkplaceSessionRepository sessions,
                          DestinationDemandRepository demands, StationQueueEntryRepository queue) {
        this.stations = stations;
        this.workCycles = workCycles;
        this.putInstructions = putInstructions;
        this.taskLines = taskLines;
        this.sessions = sessions;
        this.demands = demands;
        this.queue = queue;
    }

    /**
     * Delete all operational rows hanging off the warehouse's stations, keeping the station/node
     * configuration. One bulk DELETE per table — work cycles and put instructions accumulate
     * fast, so nothing is loaded into memory. FK-safe order: put_instruction references both
     * work_cycle and destination_demand (no cascade on the demand FK), so put instructions go
     * first, then task lines and work cycles, then sessions, and destination demand last.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        List<UUID> stationIds = stations.findByWarehouseId(warehouseId).stream()
                .map(GtpStation::getId)
                .toList();

        queue.deleteBulkByWarehouseId(warehouseId);

        if (stationIds.isEmpty()) {
            return new DemoClearResult(0, 0, 0, 0, 0);
        }

        int puts = putInstructions.deleteBulkByStationIds(stationIds);
        int lines = taskLines.deleteBulkByStationIds(stationIds);
        int cycles = workCycles.deleteBulkByStationIds(stationIds);
        int openSessions = sessions.deleteBulkByStationIds(stationIds);
        int demandRows = demands.deleteBulkByStationIds(stationIds);

        return new DemoClearResult(cycles, puts, lines, openSessions, demandRows);
    }
}
