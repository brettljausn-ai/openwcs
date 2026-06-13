package org.openwcs.flow.api;

import java.util.UUID;
import org.openwcs.flow.api.TwinPathDtos.TwinPaths;
import org.openwcs.flow.service.TwinPathService;
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

    public TwinPathController(TwinPathService service) {
        this.service = service;
    }

    @GetMapping("/tote-paths")
    public TwinPaths totePaths(@RequestParam("warehouseId") UUID warehouseId) {
        return service.paths(warehouseId);
    }
}
