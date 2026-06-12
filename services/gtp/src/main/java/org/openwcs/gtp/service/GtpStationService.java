package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.gtp.api.AddNodeRequest;
import org.openwcs.gtp.api.CreateStationRequest;
import org.openwcs.gtp.api.NotFoundException;
import org.openwcs.gtp.api.OpenDestinationRequest;
import org.openwcs.gtp.api.UpdateNodeRequest;
import org.openwcs.gtp.api.UpdateStationRequest;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Station configuration + order-destination setup for goods-to-person execution (ADR 0006):
 * create stations and their STOCK/ORDER nodes, and open order destinations by binding an order
 * HU and posting its demand. The pick-and-put cycle itself lives in {@link WorkCycleService}.
 */
@Service
public class GtpStationService {

    private static final Logger log = LoggerFactory.getLogger(GtpStationService.class);

    private static final Map<String, String> MODES = Map.of(
            "ORDER_LOCATION", "ORDER_LOCATION", "PUT_WALL", "PUT_WALL");

    private final GtpStationRepository stations;
    private final StationNodeRepository nodes;
    private final DestinationDemandRepository demands;

    public GtpStationService(GtpStationRepository stations,
                             StationNodeRepository nodes,
                             DestinationDemandRepository demands) {
        this.stations = stations;
        this.nodes = nodes;
        this.demands = demands;
    }

    @Transactional
    public GtpStation createStation(CreateStationRequest request) {
        String mode = MODES.get(request.mode());
        if (mode == null) {
            throw new IllegalArgumentException("mode must be ORDER_LOCATION or PUT_WALL");
        }
        stations.findByWarehouseIdAndCode(request.warehouseId(), request.code()).ifPresent(s -> {
            throw new IllegalStateException(
                    "station code already exists in warehouse: " + request.code());
        });

        GtpStation station = new GtpStation();
        station.setWarehouseId(request.warehouseId());
        station.setCode(request.code());
        station.setName(request.name());
        station.setMode(mode);
        station.setSupportedModeSet(parseModes(request.supportedModes()));
        stations.save(station);

        if (request.nodes() != null) {
            for (CreateStationRequest.NodeSpec spec : request.nodes()) {
                saveNode(station.getId(), spec.role(), spec.code(), spec.putLightId(),
                        spec.locationId(), spec.orderHuId(), spec.position());
            }
        }
        return station;
    }

    /**
     * Update a station's editable configuration (code, name, destination topology mode, status, and
     * optionally its supported operating modes). The warehouse is immutable. A code change is checked
     * for uniqueness within the warehouse. When {@code supportedModes} is null the current set is kept.
     */
    @Transactional
    public GtpStation updateStation(UUID stationId, UpdateStationRequest request) {
        GtpStation station = requireStation(stationId);
        String mode = MODES.get(request.mode());
        if (mode == null) {
            throw new IllegalArgumentException("mode must be ORDER_LOCATION or PUT_WALL");
        }
        if (!station.getCode().equals(request.code())) {
            stations.findByWarehouseIdAndCode(station.getWarehouseId(), request.code()).ifPresent(s -> {
                throw new IllegalStateException(
                        "station code already exists in warehouse: " + request.code());
            });
            station.setCode(request.code());
        }
        station.setName(request.name());
        station.setMode(mode);
        if (request.status() != null && !request.status().isBlank()) {
            String newStatus = request.status().trim();
            if (!newStatus.equals(station.getStatus())) {
                log.info("station {} status changed {} -> {} by configuration update",
                        station.getCode(), station.getStatus(), newStatus);
            }
            station.setStatus(newStatus);
        }
        if (request.supportedModes() != null) {
            station.setSupportedModeSet(parseModes(request.supportedModes()));
        }
        return station;
    }

    /** Delete a station (cascades to its nodes/demand/cycles via the schema's ON DELETE CASCADE). */
    @Transactional
    public void deleteStation(UUID stationId) {
        GtpStation station = requireStation(stationId);
        stations.delete(station);
    }

    /**
     * Replace the set of operating modes a station supports. A station can run any combination
     * (e.g. decanting-only); when no modes are given the set defaults to PICKING.
     */
    @Transactional
    public GtpStation setSupportedModes(UUID stationId, List<String> modes) {
        GtpStation station = requireStation(stationId);
        station.setSupportedModeSet(parseModes(modes));
        log.info("station {} supported operating modes set to {}",
                station.getCode(), station.getSupportedModes());
        return station;
    }

    /**
     * Set a station's in-transit HU caps: how many HUs may have an active transport inbound to the
     * station at once, split into PICKING and OTHER (non-picking) mode classes. Both values must be
     * non-negative. This is configuration only; the enforcement is built separately.
     */
    @Transactional
    public GtpStation setCapacity(UUID stationId, int maxInTransitPicking, int maxInTransitOther) {
        if (maxInTransitPicking < 0 || maxInTransitOther < 0) {
            throw new IllegalArgumentException("in-transit caps must be non-negative");
        }
        GtpStation station = requireStation(stationId);
        station.setMaxInTransitPicking(maxInTransitPicking);
        station.setMaxInTransitOther(maxInTransitOther);
        log.info("station {} in-transit caps set: picking {}, other {}",
                station.getCode(), maxInTransitPicking, maxInTransitOther);
        return station;
    }

    /** Parse a list of operating-mode names into an ordered set; null/empty defaults to PICKING. */
    private Set<OperatingMode> parseModes(List<String> modes) {
        Set<OperatingMode> parsed = new LinkedHashSet<>();
        if (modes != null) {
            for (String m : modes) {
                if (m != null && !m.isBlank()) {
                    parsed.add(OperatingMode.parse(m.trim()));
                }
            }
        }
        if (parsed.isEmpty()) {
            parsed.add(OperatingMode.PICKING);
        }
        return parsed;
    }

    @Transactional
    public StationNode addNode(UUID stationId, AddNodeRequest request) {
        requireStation(stationId);
        return saveNode(stationId, request.role(), request.code(), request.putLightId(),
                request.locationId(), request.orderHuId(), request.position());
    }

    /** Update an existing node's role, code, put-light/destination wiring, address, position + status. */
    @Transactional
    public StationNode updateNode(UUID nodeId, UpdateNodeRequest request) {
        StationNode node = nodes.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("station node", nodeId));
        if (!"STOCK".equals(request.role()) && !"ORDER".equals(request.role())) {
            throw new IllegalArgumentException("node role must be STOCK or ORDER");
        }
        node.setRole(request.role());
        node.setCode(request.code());
        node.setPutLightId(request.putLightId());
        node.setLocationId(request.locationId());
        node.setOrderHuId(request.orderHuId());
        if (request.position() != null) {
            node.setPosition(request.position());
        }
        if (request.status() != null && !request.status().isBlank()) {
            node.setStatus(request.status().trim());
        }
        return node;
    }

    /** Remove a node from a station. */
    @Transactional
    public void deleteNode(UUID nodeId) {
        StationNode node = nodes.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("station node", nodeId));
        nodes.delete(node);
    }

    /** A node projected from the automation topology's STOCK/ORDER conveyor interactions. */
    public record NodeSyncSpec(String role, String code, UUID locationId, String putLightId,
                               java.math.BigDecimal inboundDistanceM) {
    }

    /**
     * Replace a station's nodes from the automation topology (projected STOCK/ORDER conveyor
     * interactions). Nodes are matched by code so an existing node keeps its id and any bound order
     * HU/demand; nodes the topology no longer defines are removed when they carry no open demand.
     */
    @Transactional
    public GtpStation syncNodes(UUID stationId, List<NodeSyncSpec> specs) {
        GtpStation station = requireStation(stationId);
        List<StationNode> existing = nodes.findByStationIdOrderByPositionAsc(stationId);
        java.util.Map<String, StationNode> byCode = new java.util.HashMap<>();
        for (StationNode n : existing) {
            byCode.putIfAbsent(n.getCode(), n);
        }
        java.util.Set<String> wanted = new java.util.HashSet<>();
        int pos = 0;
        int matched = 0;
        int added = 0;
        for (NodeSyncSpec s : specs) {
            if (!"STOCK".equals(s.role()) && !"ORDER".equals(s.role())) {
                continue;
            }
            wanted.add(s.code());
            StationNode existingNode = byCode.get(s.code());
            StationNode node = existingNode != null ? existingNode : new StationNode();
            if (existingNode != null) {
                matched++;
            } else {
                added++;
            }
            node.setStationId(stationId);
            node.setRole(s.role());
            node.setCode(s.code());
            node.setPutLightId(s.putLightId());
            if (s.locationId() != null) {
                node.setLocationId(s.locationId());
            }
            node.setInboundDistanceM(s.inboundDistanceM());
            node.setPosition(pos++);
            nodes.save(node);
        }
        // Drop nodes the topology no longer defines, but only when nothing depends on them.
        int removed = 0;
        int keptWithDemand = 0;
        for (StationNode n : existing) {
            if (!wanted.contains(n.getCode())) {
                if (demands.findByStationNodeId(n.getId()).isEmpty()) {
                    nodes.delete(n);
                    removed++;
                } else {
                    keptWithDemand++;
                    log.warn("node sync at station {}: stale node {} is no longer in the topology "
                                    + "but still carries demand; kept until the demand is resolved",
                            station.getCode(), n.getCode());
                }
            }
        }
        log.info("node sync at station {}: {} node(s) from topology ({} matched by code, {} added), "
                        + "{} stale removed, {} stale kept (open demand)",
                station.getCode(), pos, matched, added, removed, keptWithDemand);
        return station;
    }

    private StationNode saveNode(UUID stationId, String role, String code, String putLightId,
                                 UUID locationId, UUID orderHuId, Integer position) {
        if (!"STOCK".equals(role) && !"ORDER".equals(role)) {
            throw new IllegalArgumentException("node role must be STOCK or ORDER");
        }
        StationNode node = new StationNode();
        node.setStationId(stationId);
        node.setRole(role);
        node.setCode(code);
        node.setPutLightId(putLightId);
        node.setLocationId(locationId);
        node.setOrderHuId(orderHuId);
        node.setPosition(position == null ? 0 : position);
        return nodes.save(node);
    }

    /**
     * Open an order destination at an ORDER node: bind the order HU and record its demand. The
     * demand is what a presented stock HU is later matched against to build the put-list.
     */
    @Transactional
    public DestinationDemand openDestination(UUID nodeId, OpenDestinationRequest request) {
        StationNode node = nodes.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("station node", nodeId));
        if (!"ORDER".equals(node.getRole())) {
            throw new IllegalArgumentException("destinations can only be opened on ORDER nodes");
        }
        node.setOrderHuId(request.orderHuId());

        DestinationDemand demand = new DestinationDemand();
        demand.setStationNodeId(nodeId);
        demand.setOrderRef(request.orderRef());
        demand.setOrderLineId(request.orderLineId());
        demand.setSkuId(request.skuId());
        demand.setRequestedQty(request.qty());
        demand.setPuttedQty(BigDecimal.ZERO);
        DestinationDemand saved = demands.save(demand);
        log.info("destination opened at node {}: order {} demands qty {} of sku {} (order hu {})",
                node.getCode(), request.orderRef(), request.qty(), request.skuId(), request.orderHuId());
        return saved;
    }

    @Transactional(readOnly = true)
    public GtpStation requireStation(UUID stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("station", stationId));
    }

    @Transactional(readOnly = true)
    public List<StationNode> nodesOf(UUID stationId) {
        return nodes.findByStationIdOrderByPositionAsc(stationId);
    }

    @Transactional(readOnly = true)
    public List<GtpStation> byWarehouse(UUID warehouseId) {
        return stations.findByWarehouseId(warehouseId);
    }

    @Transactional(readOnly = true)
    public List<DestinationDemand> demandsOfStation(UUID stationId) {
        List<DestinationDemand> result = new ArrayList<>();
        for (StationNode node : nodes.findByStationIdAndRole(stationId, "ORDER")) {
            result.addAll(demands.findByStationNodeId(node.getId()));
        }
        return result;
    }
}
