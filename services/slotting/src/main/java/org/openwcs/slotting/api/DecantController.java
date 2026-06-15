package org.openwcs.slotting.api;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.service.DispatchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decant → put-away seam (goods-in decant fast-follow, epic 1): a GTP decant produced a storable
 * HU. This runs the put-away scorer to choose a destination slot and dispatches the physical store
 * move to flow-orchestrator, returning the put-away decision plus the created device-task id.
 */
@RestController
@RequestMapping("/api/slotting/decant")
public class DecantController {

    private final DispatchService dispatch;

    public DecantController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @PostMapping("/putaway")
    public DispatchService.DecantResult putaway(
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody DecantPutawayRequest request) {
        DispatchAccess.requireOperator(roles);
        if (!AccessControl.warehouseAllowed(warehouses, request.warehouseId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
        return dispatch.dispatchDecantPutaway(request.warehouseId(), request.huId(), request.skuId(),
                request.qty(), request.fromLocationId());
    }

    /**
     * Body of a decant put-away: the storable HU, its SKU and quantity, and optionally the decant
     * station location it currently sits at (when omitted the HU's registered position is used as
     * the move's from-location).
     */
    public record DecantPutawayRequest(UUID warehouseId, UUID huId, UUID skuId, BigDecimal qty,
                                       UUID fromLocationId) {
    }
}
