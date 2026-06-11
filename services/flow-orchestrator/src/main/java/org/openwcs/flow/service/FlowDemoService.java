package org.openwcs.flow.service;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.api.DemoClearResult;
import org.openwcs.flow.domain.DeviceTask;
import org.openwcs.flow.domain.HuRoute;
import org.openwcs.flow.domain.TopologyObservation;
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
     * Delete all operational rows for a warehouse, keeping the topology configuration.
     * The three entities are independent of one another, so order is immaterial.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        List<DeviceTask> tasks = deviceTasks.findByWarehouseId(warehouseId);
        deviceTasks.deleteAll(tasks);

        List<HuRoute> routes = huRoutes.findByWarehouseId(warehouseId);
        huRoutes.deleteAll(routes);

        List<TopologyObservation> obs = observations.findByWarehouseId(warehouseId);
        observations.deleteAll(obs);

        // ADR-0007 §3c-1 induction queue + HU transport trace are transactional flow state too.
        induction.deleteAll(induction.findByWarehouseId(warehouseId));
        traces.deleteAll(traces.findByWarehouseId(warehouseId));

        return new DemoClearResult(tasks.size(), routes.size(), obs.size());
    }
}
