package org.openwcs.gtp.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.api.DemoClearResult;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.domain.TaskLine;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.domain.WorkplaceSession;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.PutInstructionRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
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
    private final StationNodeRepository nodes;
    private final WorkCycleRepository workCycles;
    private final PutInstructionRepository putInstructions;
    private final TaskLineRepository taskLines;
    private final WorkplaceSessionRepository sessions;
    private final DestinationDemandRepository demands;
    private final StationQueueEntryRepository queue;

    public GtpDemoService(GtpStationRepository stations, StationNodeRepository nodes,
                          WorkCycleRepository workCycles, PutInstructionRepository putInstructions,
                          TaskLineRepository taskLines, WorkplaceSessionRepository sessions,
                          DestinationDemandRepository demands, StationQueueEntryRepository queue) {
        this.stations = stations;
        this.nodes = nodes;
        this.workCycles = workCycles;
        this.putInstructions = putInstructions;
        this.taskLines = taskLines;
        this.sessions = sessions;
        this.demands = demands;
        this.queue = queue;
    }

    /**
     * Delete all operational rows hanging off the warehouse's stations, keeping the station/node
     * configuration. PutInstruction and TaskLine reference WorkCycle by a plain {@code workCycleId}
     * column (no JPA cascade), so they are deleted explicitly first, in FK-safe order:
     * put instructions / task lines → work cycles → workplace sessions → destination demand.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        List<UUID> stationIds = stations.findByWarehouseId(warehouseId).stream()
                .map(GtpStation::getId)
                .toList();

        queue.deleteByWarehouseId(warehouseId);

        if (stationIds.isEmpty()) {
            return new DemoClearResult(0, 0, 0, 0, 0);
        }

        List<WorkCycle> cycles = workCycles.findByStationIdIn(stationIds);
        List<UUID> cycleIds = cycles.stream().map(WorkCycle::getId).toList();

        List<PutInstruction> puts = cycleIds.isEmpty()
                ? List.of() : putInstructions.findByWorkCycleIdIn(cycleIds);
        putInstructions.deleteAll(puts);

        List<TaskLine> lines = cycleIds.isEmpty()
                ? List.of() : taskLines.findByWorkCycleIdIn(cycleIds);
        taskLines.deleteAll(lines);

        workCycles.deleteAll(cycles);

        List<WorkplaceSession> openSessions = sessions.findByStationIdIn(stationIds);
        sessions.deleteAll(openSessions);

        List<UUID> nodeIds = nodes.findByStationIdIn(stationIds).stream()
                .map(StationNode::getId)
                .toList();
        List<DestinationDemand> demandRows = nodeIds.isEmpty()
                ? List.of() : demands.findByStationNodeIdIn(nodeIds);
        demands.deleteAll(demandRows);

        return new DemoClearResult(
                cycles.size(),
                puts.size(),
                lines.size(),
                openSessions.size(),
                demandRows.size());
    }
}
