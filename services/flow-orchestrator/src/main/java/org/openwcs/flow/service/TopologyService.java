package org.openwcs.flow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a warehouse's conveyor topology (nodes + edges) — the load/save backing
 * for the admin schematic editor. Edges reference nodes by code in the API and by id internally.
 */
@Service
public class TopologyService {

    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;

    public TopologyService(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @Transactional(readOnly = true)
    public Topology get(UUID warehouseId) {
        Map<UUID, String> codeById = new HashMap<>();
        List<NodeDto> nodeDtos = new ArrayList<>();
        for (ConveyorNode n : nodes.findByWarehouseId(warehouseId)) {
            codeById.put(n.getId(), n.getCode());
            nodeDtos.add(new NodeDto(n.getCode(), n.getName(), n.getHardwareAddress(), n.getPosX(), n.getPosY()));
        }
        List<EdgeDto> edgeDtos = new ArrayList<>();
        for (ConveyorEdge e : edges.findByWarehouseId(warehouseId)) {
            edgeDtos.add(new EdgeDto(codeById.get(e.getFromNodeId()), codeById.get(e.getToNodeId()),
                    e.getExitCode(), e.getCost()));
        }
        return new Topology(nodeDtos, edgeDtos);
    }

    /** Replace the whole topology for a warehouse (the editor saves the full graph). */
    @Transactional
    public Topology replace(UUID warehouseId, Topology topology) {
        edges.deleteByWarehouseId(warehouseId);
        nodes.deleteAll(nodes.findByWarehouseId(warehouseId));
        nodes.flush();

        Map<String, UUID> idByCode = new HashMap<>();
        for (NodeDto n : topology.nodes()) {
            ConveyorNode node = new ConveyorNode();
            node.setWarehouseId(warehouseId);
            node.setCode(n.code());
            node.setName(n.name());
            node.setHardwareAddress(n.hardwareAddress());
            node.setPosX(n.posX());
            node.setPosY(n.posY());
            idByCode.put(n.code(), nodes.save(node).getId());
        }
        if (topology.edges() != null) {
            for (EdgeDto e : topology.edges()) {
                UUID from = idByCode.get(e.fromCode());
                UUID to = idByCode.get(e.toCode());
                if (from == null || to == null) {
                    throw new IllegalArgumentException(
                            "Edge references an unknown node code: " + e.fromCode() + " -> " + e.toCode());
                }
                ConveyorEdge edge = new ConveyorEdge();
                edge.setWarehouseId(warehouseId);
                edge.setFromNodeId(from);
                edge.setToNodeId(to);
                edge.setExitCode(e.exitCode());
                edge.setCost(e.cost() == null ? 1 : e.cost());
                edges.save(edge);
            }
        }
        return get(warehouseId);
    }
}
