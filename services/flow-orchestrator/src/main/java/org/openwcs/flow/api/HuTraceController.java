package org.openwcs.flow.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.flow.service.HuTraceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads an HU's transport-trace timeline (ADR-0007 §3.4): {@code hu_transport_trace} rows for the
 * HU, ts ASC. Coarse RBAC is enforced by {@code RbacFilter}.
 */
@RestController
@RequestMapping("/api/flow/hu-trace")
public class HuTraceController {

    private final HuTraceService service;

    public HuTraceController(HuTraceService service) {
        this.service = service;
    }

    @GetMapping
    public List<HuTraceView> trace(
            @RequestParam("huId") UUID huId,
            @RequestParam(value = "warehouseId", required = false) UUID warehouseId) {
        return service.timeline(huId, warehouseId);
    }
}
