package org.openwcs.iam.api;

import org.openwcs.iam.service.WarehouseAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service lookup of a user's allowed warehouses, used by the gateway to enforce
 * warehouse scope. Deliberately mapped under {@code /internal/**} (not {@code /api/**}): the
 * gateway's public route table and the nginx proxy only forward {@code /api/} and
 * {@code /actuator/}, so this endpoint is reachable only on the internal compose network and
 * carries no auth gating of its own.
 */
@RestController
@RequestMapping("/internal/warehouse-access")
public class InternalWarehouseAccessController {

    private final WarehouseAccessService service;

    public InternalWarehouseAccessController(WarehouseAccessService service) {
        this.service = service;
    }

    @GetMapping("/{username}")
    public WarehouseAccessView forUser(@PathVariable String username) {
        return service.forUser(username);
    }
}
