package org.openwcs.gtp.api;

import jakarta.validation.Valid;
import java.util.UUID;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.service.WorkCycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pick-and-put work cycle (ADR 0006): present a stock HU to a station to get its put-list,
 * confirm puts, and query/close cycle state.
 */
@RestController
@RequestMapping("/api/gtp")
public class WorkCycleController {

    private final WorkCycleService service;

    public WorkCycleController(WorkCycleService service) {
        this.service = service;
    }

    /** Present a stock HU at a station → returns the work cycle with its generated put-list. */
    @PostMapping("/stations/{stationId}/present")
    public WorkCycleView present(@PathVariable UUID stationId,
                                 @Valid @RequestBody PresentStockRequest request) {
        WorkCycle cycle = service.present(stationId, request);
        return WorkCycleView.from(cycle, service.modeOf(stationId), service.putsOf(cycle.getId()));
    }

    @GetMapping("/cycles/{cycleId}")
    public WorkCycleView cycle(@PathVariable UUID cycleId) {
        WorkCycle cycle = service.requireCycle(cycleId);
        return WorkCycleView.from(cycle, service.modeOf(cycle.getStationId()),
                service.putsOf(cycleId));
    }

    /** Confirm a put instruction (optionally short). Returns the updated instruction. */
    @PostMapping("/puts/{putInstructionId}/confirm")
    public PutInstructionView confirm(@PathVariable UUID putInstructionId,
                                      @RequestBody(required = false) ConfirmPutRequest request) {
        return PutInstructionView.from(service.confirm(putInstructionId, request));
    }

    @PostMapping("/cycles/{cycleId}/close")
    public WorkCycleView close(@PathVariable UUID cycleId) {
        WorkCycle cycle = service.close(cycleId);
        return WorkCycleView.from(cycle, service.modeOf(cycle.getStationId()),
                service.putsOf(cycleId));
    }
}
