package org.openwcs.flow.service;

import java.util.UUID;
import org.openwcs.flow.api.DemoClearResult;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.openwcs.flow.repo.HuRouteRepository;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.openwcs.flow.repo.InductionQueueEntryRepository;
import org.openwcs.flow.repo.TopologyObservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode for flow-orchestrator (build.md §4.8). A full operational reset for a warehouse:
 * purge ALL transactional flow state — device tasks, handling-unit routes and topology
 * observations — while leaving the topology configuration (conveyor nodes, edges, loops and
 * controllers) intact. Invoked when demo mode is turned off.
 */
@Service
public class FlowDemoService {

    private final DeviceTaskRepository deviceTasks;
    private final HuRouteRepository huRoutes;
    private final TopologyObservationRepository observations;
    private final InductionQueueEntryRepository induction;
    private final HuTransportTraceRepository traces;

    public FlowDemoService(DeviceTaskRepository deviceTasks, HuRouteRepository huRoutes,
                           TopologyObservationRepository observations,
                           InductionQueueEntryRepository induction,
                           HuTransportTraceRepository traces) {
        this.deviceTasks = deviceTasks;
        this.huRoutes = huRoutes;
        this.observations = observations;
        this.induction = induction;
        this.traces = traces;
    }

    /**
     * Delete all operational rows for a warehouse, keeping the topology configuration. One bulk
     * DELETE per table — device tasks and traces can run to millions of rows after a long
     * emulator session, so nothing is ever loaded into memory. The tables reference each other
     * by plain uuid columns only (no FKs), so statement order is immaterial.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        int tasks = deviceTasks.deleteBulkByWarehouseId(warehouseId);
        int routes = huRoutes.deleteBulkByWarehouseId(warehouseId);
        int obs = observations.deleteBulkByWarehouseId(warehouseId);

        // ADR-0007 §3c-1 induction queue + HU transport trace are transactional flow state too.
        induction.deleteBulkByWarehouseId(warehouseId);
        traces.deleteBulkByWarehouseId(warehouseId);

        return new DemoClearResult(tasks, routes, obs);
    }
}
