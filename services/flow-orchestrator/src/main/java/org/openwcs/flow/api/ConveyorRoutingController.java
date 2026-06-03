package org.openwcs.flow.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.openwcs.flow.api.RoutingDtos.RouteRequest;
import org.openwcs.flow.api.RoutingDtos.RouteView;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.api.RoutingDtos.ScanRequest;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.service.RoutingService;
import org.openwcs.flow.service.TopologyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Conveyor routing API (build.md §8): the topology (nodes + edges) the admin editor loads/saves,
 * handling-unit route plans, and the per-scan routing decision the adapters call. RBAC is the
 * flow {@code RbacFilter} (DEVICE_VIEW on reads, DEVICE_OPERATE on writes/scans).
 */
@RestController
@RequestMapping("/api/flow/conveyor")
public class ConveyorRoutingController {

    private final TopologyService topology;
    private final RoutingService routing;

    public ConveyorRoutingController(TopologyService topology, RoutingService routing) {
        this.topology = topology;
        this.routing = routing;
    }

    // ----------------------------------------------------------------- Topology (editor)
    @GetMapping("/topology")
    public Topology getTopology(@RequestParam UUID warehouseId) {
        return topology.get(warehouseId);
    }

    @PutMapping("/topology")
    public Topology saveTopology(@RequestParam UUID warehouseId, @RequestBody Topology body) {
        return topology.replace(warehouseId, body);
    }

    // ---------------------------------------------------------------------- Route plans
    @PostMapping("/routes")
    public RouteView assignRoute(@Valid @RequestBody RouteRequest request) {
        return routing.assignRoute(request);
    }

    @GetMapping("/routes")
    public ResponseEntity<RouteView> getRoute(@RequestParam UUID warehouseId, @RequestParam String barcode) {
        return routing.getRoute(warehouseId, barcode).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ----------------------------------------------------------------- Scan → decision
    @PostMapping("/routing-requests")
    public RoutingDecision route(@Valid @RequestBody ScanRequest scan) {
        return routing.decide(scan);
    }
}
