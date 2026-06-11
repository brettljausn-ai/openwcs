package org.openwcs.slotting.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.service.RelocationPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Dig-out planning for multi-deep channels: who blocks a retrieve, and where they go (ADR 0009). */
@RestController
@RequestMapping("/api/slotting/relocation-plan")
public class RelocationPlanController {

    private final RelocationPlanService relocation;

    public RelocationPlanController(RelocationPlanService relocation) {
        this.relocation = relocation;
    }

    @PostMapping
    public RelocationPlan plan(@RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                               @RequestBody RelocationPlanRequest request) {
        requireWarehouse(warehouses, request.warehouseId());
        return relocation.plan(request);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, java.util.UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
