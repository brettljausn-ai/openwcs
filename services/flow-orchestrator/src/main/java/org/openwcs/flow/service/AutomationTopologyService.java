package org.openwcs.flow.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.AutomationTopologyDtos.AutomationTopologyDto;
import org.openwcs.flow.api.AutomationTopologyDtos.ConnectionDto;
import org.openwcs.flow.api.AutomationTopologyDtos.FunctionPointDto;
import org.openwcs.flow.api.AutomationTopologyDtos.LevelDto;
import org.openwcs.flow.api.AutomationTopologyDtos.PlacedEquipmentDto;
import org.openwcs.flow.domain.EquipmentConnection;
import org.openwcs.flow.domain.EquipmentFunctionPoint;
import org.openwcs.flow.domain.PlacedEquipment;
import org.openwcs.flow.domain.WarehouseLevel;
import org.openwcs.flow.repo.EquipmentConnectionRepository;
import org.openwcs.flow.repo.EquipmentFunctionPointRepository;
import org.openwcs.flow.repo.PlacedEquipmentRepository;
import org.openwcs.flow.repo.WarehouseLevelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and replaces a warehouse's automation topology PLACEMENT model (levels + placed equipment +
 * connections + function points) — the load/save backing for the admin 3D placement editor,
 * mirroring {@link TopologyService} for the conveyor graph. Save is a full replace: existing rows
 * for the warehouse are deleted across all four tables, then re-inserted from the payload.
 *
 * <p>Ids carried in the DTOs are translated: connections and function points reference levels and
 * placed equipment by the ids supplied in the same payload, which are remapped to the ids of the
 * freshly-persisted rows.
 */
@Service
public class AutomationTopologyService {

    private final WarehouseLevelRepository levels;
    private final PlacedEquipmentRepository equipment;
    private final EquipmentConnectionRepository connections;
    private final EquipmentFunctionPointRepository functionPoints;

    public AutomationTopologyService(WarehouseLevelRepository levels, PlacedEquipmentRepository equipment,
                                     EquipmentConnectionRepository connections,
                                     EquipmentFunctionPointRepository functionPoints) {
        this.levels = levels;
        this.equipment = equipment;
        this.connections = connections;
        this.functionPoints = functionPoints;
    }

    @Transactional(readOnly = true)
    public AutomationTopologyDto load(UUID warehouseId) {
        List<LevelDto> levelDtos = new ArrayList<>();
        for (WarehouseLevel l : levels.findByWarehouseId(warehouseId)) {
            levelDtos.add(new LevelDto(l.getId(), l.getNumber(), l.getName(), l.getElevationM(), l.getStatus()));
        }
        List<PlacedEquipmentDto> equipmentDtos = new ArrayList<>();
        for (PlacedEquipment p : equipment.findByWarehouseId(warehouseId)) {
            equipmentDtos.add(new PlacedEquipmentDto(p.getId(), p.getLevelId(), p.getEquipmentId(), p.getCode(),
                    p.getPosXM(), p.getPosYM(), p.getPosZM(), p.getRotationDeg(), p.getTiltDeg(),
                    p.getLengthM(), p.getWidthM(), p.getHeightM(), p.getStatus()));
        }
        List<ConnectionDto> connectionDtos = new ArrayList<>();
        for (EquipmentConnection c : connections.findByWarehouseId(warehouseId)) {
            connectionDtos.add(new ConnectionDto(c.getId(), c.getFromPlacedId(), c.getToPlacedId(),
                    c.getFromPointId(), c.getToPointId(), c.getLabel(), c.getStatus()));
        }
        List<FunctionPointDto> pointDtos = new ArrayList<>();
        for (EquipmentFunctionPoint fp : functionPoints.findByWarehouseId(warehouseId)) {
            pointDtos.add(new FunctionPointDto(fp.getId(), fp.getPlacedId(), fp.getFunctionType(), fp.getName(),
                    fp.getOffsetM(), fp.getSide(), fp.getNodeCode(), fp.getStatus()));
        }
        return new AutomationTopologyDto(levelDtos, equipmentDtos, connectionDtos, pointDtos);
    }

    /** Replace the whole automation topology for a warehouse (the editor saves the full model). */
    @Transactional
    public AutomationTopologyDto save(UUID warehouseId, AutomationTopologyDto body) {
        connections.deleteByWarehouseId(warehouseId);
        functionPoints.deleteByWarehouseId(warehouseId);
        equipment.deleteByWarehouseId(warehouseId);
        levels.deleteByWarehouseId(warehouseId);
        levels.flush();

        // Translate the ids supplied in the payload to the ids of the freshly-persisted rows.
        Map<UUID, UUID> levelIdMap = new HashMap<>();
        if (body.levels() != null) {
            for (LevelDto l : body.levels()) {
                WarehouseLevel level = new WarehouseLevel();
                level.setWarehouseId(warehouseId);
                level.setNumber(l.number());
                level.setName(l.name());
                level.setElevationM(l.elevationM());
                level.setStatus(l.status() == null ? "ACTIVE" : l.status());
                UUID newId = levels.save(level).getId();
                if (l.id() != null) {
                    levelIdMap.put(l.id(), newId);
                }
            }
        }

        Map<UUID, UUID> placedIdMap = new HashMap<>();
        if (body.equipment() != null) {
            for (PlacedEquipmentDto p : body.equipment()) {
                PlacedEquipment placed = new PlacedEquipment();
                placed.setWarehouseId(warehouseId);
                placed.setLevelId(mapId(levelIdMap, p.levelId()));
                placed.setEquipmentId(p.equipmentId());
                placed.setCode(p.code());
                placed.setPosXM(p.posXM());
                placed.setPosYM(p.posYM());
                placed.setPosZM(p.posZM());
                placed.setRotationDeg(p.rotationDeg());
                placed.setTiltDeg(p.tiltDeg());
                placed.setLengthM(p.lengthM());
                placed.setWidthM(p.widthM());
                placed.setHeightM(p.heightM());
                placed.setStatus(p.status() == null ? "ACTIVE" : p.status());
                UUID newId = equipment.save(placed).getId();
                if (p.id() != null) {
                    placedIdMap.put(p.id(), newId);
                }
            }
        }

        Map<UUID, UUID> pointIdMap = new HashMap<>();
        if (body.functionPoints() != null) {
            for (FunctionPointDto fp : body.functionPoints()) {
                EquipmentFunctionPoint point = new EquipmentFunctionPoint();
                point.setWarehouseId(warehouseId);
                point.setPlacedId(mapId(placedIdMap, fp.placedId()));
                point.setFunctionType(fp.functionType());
                point.setName(fp.name());
                point.setOffsetM(fp.offsetM());
                point.setSide(fp.side());
                point.setNodeCode(fp.nodeCode());
                point.setStatus(fp.status() == null ? "ACTIVE" : fp.status());
                UUID newId = functionPoints.save(point).getId();
                if (fp.id() != null) {
                    pointIdMap.put(fp.id(), newId);
                }
            }
        }

        if (body.connections() != null) {
            for (ConnectionDto c : body.connections()) {
                EquipmentConnection conn = new EquipmentConnection();
                conn.setWarehouseId(warehouseId);
                conn.setFromPlacedId(mapId(placedIdMap, c.fromPlacedId()));
                conn.setToPlacedId(mapId(placedIdMap, c.toPlacedId()));
                conn.setFromPointId(mapId(pointIdMap, c.fromPointId()));
                conn.setToPointId(mapId(pointIdMap, c.toPointId()));
                conn.setLabel(c.label());
                conn.setStatus(c.status() == null ? "ACTIVE" : c.status());
                connections.save(conn);
            }
        }

        return load(warehouseId);
    }

    /** Resolve a payload-supplied id to its persisted id; pass through unknown/absent ids unchanged. */
    private static UUID mapId(Map<UUID, UUID> idMap, UUID supplied) {
        if (supplied == null) {
            return null;
        }
        return idMap.getOrDefault(supplied, supplied);
    }
}
