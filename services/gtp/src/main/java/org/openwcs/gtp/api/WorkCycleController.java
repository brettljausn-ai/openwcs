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

    /**
     * Present a stock HU at a station → returns the PICKING work cycle with its generated put-list.
     * (Shorthand for {@code startCycle} with operatingMode = PICKING.)
     */
    @PostMapping("/stations/{stationId}/present")
    public WorkCycleView present(@PathVariable UUID stationId,
                                 @Valid @RequestBody PresentStockRequest request) {
        WorkCycle cycle = service.present(stationId, request);
        return view(cycle);
    }

    /**
     * Open/start a work cycle in a given operating mode (PICKING | DECANTING | STOCK_COUNT | QC |
     * MAINTENANCE) and present its HU(s) → returns the cycle with its mode-appropriate task lines
     * (or put-list, for PICKING).
     */
    @PostMapping("/stations/{stationId}/cycles")
    public WorkCycleView startCycle(@PathVariable UUID stationId,
                                    @Valid @RequestBody StartCycleRequest request) {
        WorkCycle cycle = service.startCycle(stationId, request);
        return view(cycle);
    }

    @GetMapping("/cycles/{cycleId}")
    public WorkCycleView cycle(@PathVariable UUID cycleId) {
        return view(service.requireCycle(cycleId));
    }

    /** Confirm a put instruction (optionally short). Returns the updated instruction. */
    @PostMapping("/puts/{putInstructionId}/confirm")
    public PutInstructionView confirm(@PathVariable UUID putInstructionId,
                                      @RequestBody(required = false) ConfirmPutRequest request) {
        return PutInstructionView.from(service.confirm(putInstructionId, request));
    }

    /**
     * Submit the per-line outcome of a non-PICKING task line (decant move qty / counted qty /
     * QC verdict / maintenance check). Returns the updated, confirmed task line.
     */
    @PostMapping("/tasks/{taskLineId}/outcome")
    public TaskLineView submitOutcome(@PathVariable UUID taskLineId,
                                      @RequestBody(required = false) SubmitOutcomeRequest request) {
        return TaskLineView.from(service.submitOutcome(taskLineId, request));
    }

    @PostMapping("/cycles/{cycleId}/close")
    public WorkCycleView close(@PathVariable UUID cycleId) {
        return view(service.close(cycleId));
    }

    private WorkCycleView view(WorkCycle cycle) {
        return WorkCycleView.from(cycle, service.modeOf(cycle.getStationId()),
                service.putsOf(cycle.getId()), service.taskLinesOf(cycle.getId()));
    }
}
