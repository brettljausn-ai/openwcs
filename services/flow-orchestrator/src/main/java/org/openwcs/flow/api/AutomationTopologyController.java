package org.openwcs.flow.api;

import java.util.UUID;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.service.AutomationTopologyService;
import org.openwcs.flow.service.RoutingProjectionService;
import org.openwcs.flow.service.RoutingProjectionService.ProjectionResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Automation topology PLACEMENT API (Phase 1): the levels + placed equipment + connections +
 * function points the admin 3D editor loads/saves for a warehouse. Mirrors
 * {@link ConveyorRoutingController}'s topology load/replace. RBAC is the flow {@code RbacFilter}
 * (DEVICE_VIEW on reads, DEVICE_OPERATE on writes).
 */
@RestController
@RequestMapping("/api/flow/automation")
public class AutomationTopologyController {

    private final AutomationTopologyService topology;
    private final RoutingProjectionService routingProjection;

    public AutomationTopologyController(AutomationTopologyService topology,
                                        RoutingProjectionService routingProjection) {
        this.topology = topology;
        this.routingProjection = routingProjection;
    }

    @GetMapping("/topology")
    public AutomationTopologyDto getTopology(@RequestParam UUID warehouseId) {
        return topology.load(warehouseId);
    }

    @PutMapping("/topology")
    public AutomationTopologyDto saveTopology(@RequestParam UUID warehouseId,
                                              @RequestBody AutomationTopologyDto body) {
        return topology.save(warehouseId, body);
    }

    /**
     * Project the placement model into the conveyor routing graph (nodes + edges), fully replacing
     * the warehouse's existing graph. A write — covered by the flow {@code RbacFilter}'s
     * DEVICE_OPERATE check on POST.
     */
    @PostMapping("/topology/project")
    public ProjectionResult projectTopology(@RequestParam UUID warehouseId) {
        return routingProjection.project(warehouseId);
    }
}
