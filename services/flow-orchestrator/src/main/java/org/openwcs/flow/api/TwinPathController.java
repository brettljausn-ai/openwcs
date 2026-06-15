package org.openwcs.flow.api;

import java.util.UUID;
import org.openwcs.flow.api.TwinPathDtos.TwinPaths;
import org.openwcs.flow.api.TwinTelemetryDtos.AmrFleet;
import org.openwcs.flow.api.TwinTelemetryDtos.AsrsCranes;
import org.openwcs.flow.api.TwinTelemetryDtos.Autostore;
import org.openwcs.flow.service.TwinPathService;
import org.openwcs.flow.service.TwinTelemetryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live-twin tote paths (the "visu master" read model). One GET returns every moving tote's resolved
 * conveyor polyline plus the server clock, so the Hardware visualisation page renders motion by
 * playing a backend-supplied path rather than reconstructing it from a thin scan feed. Coarse RBAC
 * is enforced by {@code RbacFilter}.
 */
@RestController
@RequestMapping("/api/flow/twin")
public class TwinPathController {

    private final TwinPathService service;
    private final TwinTelemetryService telemetry;

    public TwinPathController(TwinPathService service, TwinTelemetryService telemetry) {
        this.service = service;
        this.telemetry = telemetry;
    }

    @GetMapping("/tote-paths")
    public TwinPaths totePaths(@RequestParam("warehouseId") UUID warehouseId) {
        return service.paths(warehouseId);
    }

    /**
     * Live AMR fleet: per active robot, its world XZ position, status and carried HU. Best-effort —
     * an empty fleet when the emulator is off/unreachable. Requires DEVICE_VIEW.
     */
    @GetMapping("/amr-fleet")
    public AmrFleet amrFleet(@RequestParam("warehouseId") UUID warehouseId) {
        return telemetry.amrFleet(warehouseId);
    }

    /**
     * Live AutoStore grid fill + port activity. Best-effort — a zeroed grid / empty ports when the
     * emulator is off/unreachable. Requires DEVICE_VIEW.
     */
    @GetMapping("/autostore")
    public Autostore autostore(@RequestParam("warehouseId") UUID warehouseId) {
        return telemetry.autostore(warehouseId);
    }

    /**
     * Live ASRS cranes: per crane, its world coordinates (x/z metres, y lift height), status and
     * carried HU. Best-effort — an empty list when the emulator is off/unreachable. Requires
     * DEVICE_VIEW.
     */
    @GetMapping("/asrs-cranes")
    public AsrsCranes asrsCranes(@RequestParam("warehouseId") UUID warehouseId) {
        return telemetry.asrsCranes(warehouseId);
    }
}
