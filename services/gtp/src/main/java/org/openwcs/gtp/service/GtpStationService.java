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
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Station configuration + order-destination setup for goods-to-person execution (ADR 0006):
 * create stations and their STOCK/ORDER nodes, and open order destinations by binding an order
 * HU and posting its demand. The pick-and-put cycle itself lives in {@link WorkCycleService}.
 */
@Service
public class GtpStationService {

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
     * Replace the set of operating modes a station supports. An empty/blank list is rejected; PICKING
     * is always retained so a station never loses the base flow.
     */
    @Transactional
    public GtpStation setSupportedModes(UUID stationId, List<String> modes) {
        GtpStation station = requireStation(stationId);
        Set<OperatingMode> parsed = parseModes(modes);
        parsed.add(OperatingMode.PICKING);
        station.setSupportedModeSet(parsed);
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
        return demands.save(demand);
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
