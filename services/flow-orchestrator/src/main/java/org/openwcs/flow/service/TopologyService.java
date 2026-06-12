package org.openwcs.flow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.RoutingDtos.ControllerDto;
import org.openwcs.flow.api.RoutingDtos.EdgeDto;
import org.openwcs.flow.api.RoutingDtos.LoopDto;
import org.openwcs.flow.api.RoutingDtos.NodeDto;
import org.openwcs.flow.api.RoutingDtos.Topology;
import org.openwcs.flow.domain.ConveyorController;
import org.openwcs.flow.domain.ConveyorEdge;
import org.openwcs.flow.domain.ConveyorLoop;
import org.openwcs.flow.domain.ConveyorNode;
import org.openwcs.flow.repo.ConveyorControllerRepository;
import org.openwcs.flow.repo.ConveyorEdgeRepository;
import org.openwcs.flow.repo.ConveyorLoopRepository;
import org.openwcs.flow.repo.ConveyorNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a warehouse's conveyor topology (nodes + edges + loops) — the load/save
 * backing for the admin schematic editor. Edges/loops reference nodes by code in the API and by
 * id internally.
 */
@Service
public class TopologyService {

    private static final Logger log = LoggerFactory.getLogger(TopologyService.class);

    private final ConveyorNodeRepository nodes;
    private final ConveyorEdgeRepository edges;
    private final ConveyorLoopRepository loops;
    private final ConveyorControllerRepository controllers;
    private final RoutingGraphCache graphCache;

    public TopologyService(ConveyorNodeRepository nodes, ConveyorEdgeRepository edges, ConveyorLoopRepository loops,
                           ConveyorControllerRepository controllers, RoutingGraphCache graphCache) {
        this.nodes = nodes;
        this.edges = edges;
        this.loops = loops;
        this.controllers = controllers;
        this.graphCache = graphCache;
    }

    @Transactional(readOnly = true)
    public Topology get(UUID warehouseId) {
        Map<UUID, String> codeById = new HashMap<>();
        List<NodeDto> nodeDtos = new ArrayList<>();
        for (ConveyorNode n : nodes.findByWarehouseId(warehouseId)) {
            codeById.put(n.getId(), n.getCode());
            nodeDtos.add(new NodeDto(n.getCode(), n.getName(), n.getHardwareAddress(),
                    n.getPosX(), n.getPosY(), n.getLoopCode(), n.getControllerCode(), n.getNodeAddress(),
                    n.getDefaultExitCode()));
        }
        List<EdgeDto> edgeDtos = new ArrayList<>();
        for (ConveyorEdge e : edges.findByWarehouseId(warehouseId)) {
            edgeDtos.add(new EdgeDto(codeById.get(e.getFromNodeId()), codeById.get(e.getToNodeId()),
                    e.getExitCode(), e.getCost()));
        }
        List<LoopDto> loopDtos = new ArrayList<>();
        for (ConveyorLoop l : loops.findByWarehouseId(warehouseId)) {
            loopDtos.add(new LoopDto(l.getCode(), l.getMaxHus(), l.getWhenFull(), l.getOverflowTargetCode()));
        }
        List<ControllerDto> controllerDtos = new ArrayList<>();
        for (ConveyorController c : controllers.findByWarehouseId(warehouseId)) {
            controllerDtos.add(new ControllerDto(c.getCode(), c.getName(), c.getIpAddress(), c.getPort()));
        }
        return new Topology(nodeDtos, edgeDtos, loopDtos, controllerDtos);
    }

    /** Replace the whole topology for a warehouse (the editor saves the full graph). */
    @Transactional
    public Topology replace(UUID warehouseId, Topology topology) {
        edges.deleteByWarehouseId(warehouseId);
        loops.deleteByWarehouseId(warehouseId);
        controllers.deleteByWarehouseId(warehouseId);
        nodes.deleteAll(nodes.findByWarehouseId(warehouseId));
        nodes.flush();

        if (topology.controllers() != null) {
            for (ControllerDto c : topology.controllers()) {
                ConveyorController controller = new ConveyorController();
                controller.setWarehouseId(warehouseId);
                controller.setCode(c.code());
                controller.setName(c.name());
                controller.setIpAddress(c.ipAddress());
                controller.setPort(c.port());
                controllers.save(controller);
            }
        }

        Map<String, UUID> idByCode = new HashMap<>();
        for (NodeDto n : topology.nodes()) {
            ConveyorNode node = new ConveyorNode();
            node.setWarehouseId(warehouseId);
            node.setCode(n.code());
            node.setName(n.name());
            node.setHardwareAddress(n.hardwareAddress());
            node.setControllerCode(n.controllerCode());
            node.setNodeAddress(n.nodeAddress());
            node.setPosX(n.posX());
            node.setPosY(n.posY());
            node.setLoopCode(n.loopCode());
            node.setDefaultExitCode(n.defaultExitCode());
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
        if (topology.loops() != null) {
            for (LoopDto l : topology.loops()) {
                ConveyorLoop loop = new ConveyorLoop();
                loop.setWarehouseId(warehouseId);
                loop.setCode(l.code());
                loop.setMaxHus(l.maxHus());
                loop.setWhenFull(l.whenFull() == null ? "HOLD" : l.whenFull());
                loop.setOverflowTargetCode(l.overflowTarget());
                loops.save(loop);
            }
        }
        log.info("conveyor topology replaced for warehouse {} (editor save): {} nodes, {} edges, "
                        + "{} loops, {} controllers", warehouseId, topology.nodes().size(),
                topology.edges() == null ? 0 : topology.edges().size(),
                topology.loops() == null ? 0 : topology.loops().size(),
                topology.controllers() == null ? 0 : topology.controllers().size());
        // The routing fast path serves from a per-warehouse graph snapshot; drop it once this
        // replace commits so the very next scan routes over the edited graph.
        graphCache.evictAfterCommit(warehouseId);
        return get(warehouseId);
    }
}
