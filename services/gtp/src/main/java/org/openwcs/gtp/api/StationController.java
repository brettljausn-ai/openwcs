package org.openwcs.gtp.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.service.GtpStationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Configure GTP stations + nodes and open order destinations (ADR 0006). */
@RestController
@RequestMapping("/api/gtp/stations")
public class StationController {

    private final GtpStationService service;

    public StationController(GtpStationService service) {
        this.service = service;
    }

    @PostMapping
    public StationView create(@Valid @RequestBody CreateStationRequest request) {
        GtpStation station = service.createStation(request);
        return StationView.from(station, service.nodesOf(station.getId()));
    }

    @GetMapping
    public List<StationView> byWarehouse(@RequestParam("warehouseId") UUID warehouseId) {
        return service.byWarehouse(warehouseId).stream()
                .map(s -> StationView.from(s, service.nodesOf(s.getId())))
                .toList();
    }

    @GetMapping("/{stationId}")
    public StationView get(@PathVariable UUID stationId) {
        GtpStation station = service.requireStation(stationId);
        return StationView.from(station, service.nodesOf(stationId));
    }

    /** Update a station's editable configuration (code, name, mode, status, supported modes). */
    @PutMapping("/{stationId}")
    public StationView update(@PathVariable UUID stationId,
                             @Valid @RequestBody UpdateStationRequest request) {
        GtpStation station = service.updateStation(stationId, request);
        return StationView.from(station, service.nodesOf(stationId));
    }

    /** Delete a station and (cascading) its nodes, demand and cycles. */
    @DeleteMapping("/{stationId}")
    public ResponseEntity<Void> delete(@PathVariable UUID stationId) {
        service.deleteStation(stationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{stationId}/nodes")
    public StationView.NodeView addNode(@PathVariable UUID stationId,
                                        @Valid @RequestBody AddNodeRequest request) {
        StationNode node = service.addNode(stationId, request);
        return StationView.NodeView.from(node);
    }

    /** Update a node's role, code, put-light/destination wiring, address, position and status. */
    @PutMapping("/nodes/{nodeId}")
    public StationView.NodeView updateNode(@PathVariable UUID nodeId,
                                           @Valid @RequestBody UpdateNodeRequest request) {
        StationNode node = service.updateNode(nodeId, request);
        return StationView.NodeView.from(node);
    }

    /** Remove a node from a station. */
    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<Void> deleteNode(@PathVariable UUID nodeId) {
        service.deleteNode(nodeId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Replace a station's STOCK/ORDER nodes from the automation topology (projected conveyor
     * interactions). Called by the flow-orchestrator when the topology is projected; carries the
     * feeding conveyor distance so the emulator can time tote arrivals.
     */
    @PostMapping("/{stationId}/nodes/sync")
    public StationView syncNodes(@PathVariable UUID stationId, @RequestBody SyncNodesRequest request) {
        List<GtpStationService.NodeSyncSpec> specs = (request.nodes() == null ? List.<SyncNodesRequest.NodeSpec>of() : request.nodes())
                .stream()
                .map(n -> new GtpStationService.NodeSyncSpec(n.role(), n.code(), n.locationId(), n.putLightId(), n.inboundDistanceM()))
                .toList();
        GtpStation station = service.syncNodes(stationId, specs);
        return StationView.from(station, service.nodesOf(stationId));
    }

    /** The topology-projected node set for a station. */
    public record SyncNodesRequest(List<NodeSpec> nodes) {
        public record NodeSpec(String role, String code, UUID locationId, String putLightId,
                               java.math.BigDecimal inboundDistanceM) {
        }
    }

    /** Configure the operating modes a station supports (PICKING is always retained). */
    @PostMapping("/{stationId}/operating-modes")
    public StationView setSupportedModes(@PathVariable UUID stationId,
                                         @Valid @RequestBody SetSupportedModesRequest request) {
        GtpStation station = service.setSupportedModes(stationId, request.supportedModes());
        return StationView.from(station, service.nodesOf(stationId));
    }

    /** Configure the station's in-transit HU caps (max inbound transports, split PICKING vs OTHER). */
    @PostMapping("/{stationId}/capacity")
    public StationView setCapacity(@PathVariable UUID stationId,
                                   @Valid @RequestBody SetCapacityRequest request) {
        GtpStation station = service.setCapacity(stationId,
                request.maxInTransitPicking(), request.maxInTransitOther());
        return StationView.from(station, service.nodesOf(stationId));
    }

    @GetMapping("/{stationId}/demand")
    public List<DemandView> demand(@PathVariable UUID stationId) {
        service.requireStation(stationId);
        return service.demandsOfStation(stationId).stream().map(DemandView::from).toList();
    }

    @PostMapping("/nodes/{nodeId}/destinations")
    public DemandView openDestination(@PathVariable UUID nodeId,
                                      @Valid @RequestBody OpenDestinationRequest request) {
        return DemandView.from(service.openDestination(nodeId, request));
    }
}
