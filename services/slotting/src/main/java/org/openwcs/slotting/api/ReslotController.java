package org.openwcs.slotting.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.repo.ReslotRecommendationRepository;
import org.openwcs.slotting.service.DispatchService;
import org.openwcs.slotting.service.ReslotService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Re-slotting: generate + list move recommendations, and dispatch them as physical moves (ADR 0003). */
@RestController
@RequestMapping("/api/slotting/reslot")
public class ReslotController {

    private final ReslotService reslot;
    private final DispatchService dispatch;
    private final ReslotRecommendationRepository recommendations;

    public ReslotController(ReslotService reslot, DispatchService dispatch,
                            ReslotRecommendationRepository recommendations) {
        this.reslot = reslot;
        this.dispatch = dispatch;
        this.recommendations = recommendations;
    }

    /** Run re-slotting now — a single block if {@code blockId} is given, else all enabled blocks. */
    @PostMapping("/recommend")
    public List<ReslotRecommendation> recommend(@RequestParam UUID warehouseId,
                                                @RequestParam(required = false) UUID blockId) {
        return blockId != null
                ? reslot.recommendForBlock(warehouseId, blockId)
                : reslot.recommend(warehouseId);
    }

    @GetMapping("/recommendations")
    public List<ReslotRecommendation> list(@RequestParam UUID warehouseId,
                                           @RequestParam(defaultValue = "RECOMMENDED") String status) {
        return recommendations.findByWarehouseIdAndStatus(warehouseId, status);
    }

    /**
     * Dispatch a RECOMMENDED recommendation to flow-orchestrator as a physical move (operator/admin).
     * 409 if it is not RECOMMENDED; the flow call is best-effort (a hiccup leaves it un-dispatched).
     */
    @PostMapping("/{id}/dispatch")
    public ReslotRecommendation dispatch(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses) {
        DispatchAccess.requireOperator(roles);
        ReslotRecommendation rec = recommendations.findById(id)
                .orElseThrow(() -> new NotFoundException("re-slot recommendation " + id + " not found"));
        if (!AccessControl.warehouseAllowed(warehouses, rec.getWarehouseId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
        return dispatch.dispatchReslot(id);
    }
}
