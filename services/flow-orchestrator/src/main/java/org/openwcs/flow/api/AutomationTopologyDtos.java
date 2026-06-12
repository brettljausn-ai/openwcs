package org.openwcs.flow.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request/response shapes for the automation topology PLACEMENT model (Phase 1): warehouse levels,
 * placed equipment with position/size/rotation, equipment-to-equipment connections and function
 * points on placed equipment. This is the full-graph load/save shape the admin 3D editor uses,
 * mirroring the conveyor {@link RoutingDtos.Topology}.
 *
 * <p>Ids are carried in the DTOs: on save they are preserved/translated where present so that
 * connections and function points can reference levels/placed equipment/points by id within the
 * same payload.
 */
public final class AutomationTopologyDtos {

    private AutomationTopologyDtos() {
    }

    /** The whole automation topology for a warehouse — load/save shape for the 3D editor. */
    public record AutomationTopologyDto(List<LevelDto> levels, List<PlacedEquipmentDto> equipment,
                                        List<ConnectionDto> connections, List<FunctionPointDto> functionPoints) {
    }

    /** A warehouse level (floor) with a number and floor elevation in metres. */
    public record LevelDto(UUID id, int number, String name, BigDecimal elevationM, String status) {
    }

    /** A placed piece of master-data equipment: where it sits and its placed envelope, all in metres/degrees. */
    public record PlacedEquipmentDto(UUID id, UUID levelId, UUID equipmentId, String code,
                                     BigDecimal posXM, BigDecimal posYM, BigDecimal posZM,
                                     BigDecimal rotationDeg, BigDecimal tiltDeg,
                                     BigDecimal lengthM, BigDecimal widthM, BigDecimal heightM,
                                     List<List<Double>> path, boolean closed,
                                     List<List<Integer>> sections,
                                     String status, String category, UUID stationId) {
    }

    /** A directed connection between two placed pieces of equipment, optionally anchored at function points. */
    public record ConnectionDto(UUID id, UUID fromPlacedId, UUID toPlacedId, UUID fromPointId, UUID toPointId,
                                String label, String status) {
    }

    /**
     * A function point on a placed piece of equipment (scan, divert, induct, …) at an offset along it.
     * For a divert, {@code defaultExit} is the optional default direction a tote takes when it has no
     * route demanding otherwise: STRAIGHT (continue the main line) or BRANCH (take the divert's
     * branch); null means no default (an unrouted tote stops at the divert).
     */
    public record FunctionPointDto(UUID id, UUID placedId, String functionType, String name,
                                   BigDecimal offsetM, String side, String nodeCode, String defaultExit,
                                   String status) {
    }
}
