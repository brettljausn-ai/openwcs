package org.openwcs.slotting.api;

import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.service.PutawayService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Put-away: decide where an inbound handling unit should go (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/putaway")
public class PutawayController {

    private final PutawayService putaway;

    public PutawayController(PutawayService putaway) {
        this.putaway = putaway;
    }

    @PostMapping
    public PutawayDecision assign(@RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                                  @RequestBody PutawayRequest request) {
        requireWarehouse(warehouses, request.warehouseId());
        return putaway.assign(request);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, java.util.UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
