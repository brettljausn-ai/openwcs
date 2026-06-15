package org.openwcs.flow.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.flow.api.TwinTelemetryDtos.Amr;
import org.openwcs.flow.api.TwinTelemetryDtos.AmrFleet;
import org.openwcs.flow.api.TwinTelemetryDtos.Autostore;
import org.openwcs.flow.api.TwinTelemetryDtos.Grid;
import org.openwcs.flow.api.TwinTelemetryDtos.Port;
import org.openwcs.flow.client.EmulatorTwinClient;
import org.openwcs.flow.client.EmulatorTwinClient.AmrTelemetry;
import org.openwcs.flow.client.EmulatorTwinClient.AutostoreTelemetry;
import org.openwcs.flow.client.EmulatorTwinClient.PortTelemetry;
import org.openwcs.flow.domain.PlacedEquipment;
import org.openwcs.flow.repo.PlacedEquipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live-twin telemetry read model for AMR and AutoStore (execution-layer item 3). It maps the
 * equipment-emulator's robot/grid telemetry into world XZ metres for the hardware visualisation,
 * falling back to {@code placed_equipment} positions when the emulator does not carry coordinates.
 *
 * <p>Best-effort: when the emulator is off or unreachable (the client throws), each endpoint returns
 * an empty fleet / zeroed grid rather than an error, so the twin page stays up.
 */
@Service
public class TwinTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TwinTelemetryService.class);

    private final EmulatorTwinClient emulator;
    private final PlacedEquipmentRepository equipment;

    public TwinTelemetryService(EmulatorTwinClient emulator, PlacedEquipmentRepository equipment) {
        this.emulator = emulator;
        this.equipment = equipment;
    }

    /**
     * Per active AMR robot: world XZ position (metres), status (IDLE/MOVING/FAULTED) and carried HU.
     * Positions come from the emulator; where it omits them, they are resolved from the placed AMR
     * equipment whose code matches the robot id.
     */
    @Transactional(readOnly = true)
    public AmrFleet amrFleet(UUID warehouseId) {
        long nowMs = Instant.now().toEpochMilli();
        List<AmrTelemetry> fleet;
        try {
            fleet = emulator.amrFleet();
        } catch (RuntimeException e) {
            log.debug("amr-fleet twin: emulator unreachable for warehouse {}, returning empty: {}",
                    warehouseId, e.toString());
            return new AmrFleet(nowMs, List.of());
        }

        Map<String, PlacedEquipment> byCode = equipmentByCode(warehouseId);
        List<Amr> amrs = new ArrayList<>(fleet.size());
        for (AmrTelemetry t : fleet) {
            if (t == null || t.amrId() == null) {
                continue;
            }
            double[] xz = worldXz(t.x(), t.z(), byCode.get(t.amrId()));
            amrs.add(new Amr(t.amrId(), xz[0], xz[1],
                    t.status() == null ? "IDLE" : t.status(), t.huCode()));
        }
        log.debug("amr-fleet twin for warehouse {}: {} robots", warehouseId, amrs.size());
        return new AmrFleet(nowMs, amrs);
    }

    /**
     * AutoStore grid fill + port activity. Grid counts come from the emulator; port positions come
     * from the emulator where present, else from the placed equipment whose code matches the port id.
     */
    @Transactional(readOnly = true)
    public Autostore autostore(UUID warehouseId) {
        long nowMs = Instant.now().toEpochMilli();
        AutostoreTelemetry telemetry;
        try {
            telemetry = emulator.autostoreGrid();
        } catch (RuntimeException e) {
            log.debug("autostore twin: emulator unreachable for warehouse {}, returning empty: {}",
                    warehouseId, e.toString());
            return new Autostore(nowMs, new Grid(0, 0, 0, 0), List.of());
        }
        if (telemetry == null) {
            return new Autostore(nowMs, new Grid(0, 0, 0, 0), List.of());
        }

        Grid grid = new Grid(intOr(telemetry.columns()), intOr(telemetry.rows()),
                intOr(telemetry.occupiedBins()), intOr(telemetry.totalBins()));

        Map<String, PlacedEquipment> byCode = equipmentByCode(warehouseId);
        List<PortTelemetry> portTelemetry = telemetry.ports() == null ? List.of() : telemetry.ports();
        List<Port> ports = new ArrayList<>(portTelemetry.size());
        for (PortTelemetry p : portTelemetry) {
            if (p == null || p.portId() == null) {
                continue;
            }
            double[] xz = worldXz(p.x(), p.z(), byCode.get(p.portId()));
            ports.add(new Port(p.portId(), xz[0], xz[1],
                    Boolean.TRUE.equals(p.busy()), p.huCode()));
        }
        log.debug("autostore twin for warehouse {}: {}/{} bins, {} ports", warehouseId,
                grid.occupiedBins(), grid.totalBins(), ports.size());
        return new Autostore(nowMs, grid, ports);
    }

    /** Placed equipment indexed by code (last write wins), for position fallback resolution. */
    private Map<String, PlacedEquipment> equipmentByCode(UUID warehouseId) {
        Map<String, PlacedEquipment> byCode = new HashMap<>();
        for (PlacedEquipment e : equipment.findByWarehouseId(warehouseId)) {
            if (e.getCode() != null) {
                byCode.put(e.getCode(), e);
            }
        }
        return byCode;
    }

    /**
     * World XZ in metres: the emulator coordinates when present, else the placed equipment's
     * {@code pos_x_m}/{@code pos_z_m}, else origin. Z is the world depth axis (master-data's pos_z_m),
     * matching the tote-path waypoint convention.
     */
    private static double[] worldXz(Double emX, Double emZ, PlacedEquipment placed) {
        double x = emX != null ? emX : asDouble(placed == null ? null : placed.getPosXM());
        double z = emZ != null ? emZ : asDouble(placed == null ? null : placed.getPosZM());
        return new double[]{x, z};
    }

    private static double asDouble(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }

    private static int intOr(Integer v) {
        return v == null ? 0 : v;
    }
}
