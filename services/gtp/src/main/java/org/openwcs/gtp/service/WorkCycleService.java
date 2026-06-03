package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.openwcs.gtp.api.ConfirmPutRequest;
import org.openwcs.gtp.api.NotFoundException;
import org.openwcs.gtp.api.PresentStockRequest;
import org.openwcs.gtp.api.StartCycleRequest;
import org.openwcs.gtp.api.SubmitOutcomeRequest;
import org.openwcs.gtp.domain.DestinationDemand;
import org.openwcs.gtp.domain.GtpStation;
import org.openwcs.gtp.domain.OperatingMode;
import org.openwcs.gtp.domain.PutInstruction;
import org.openwcs.gtp.domain.StationNode;
import org.openwcs.gtp.domain.TaskLine;
import org.openwcs.gtp.domain.WorkCycle;
import org.openwcs.gtp.repo.DestinationDemandRepository;
import org.openwcs.gtp.repo.GtpStationRepository;
import org.openwcs.gtp.repo.PutInstructionRepository;
import org.openwcs.gtp.repo.StationNodeRepository;
import org.openwcs.gtp.repo.TaskLineRepository;
import org.openwcs.gtp.repo.WorkCycleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pick-and-put work cycle (ADR 0006). Presenting a stock HU (SKU + qty) at a station matches
 * the SKU against open per-destination demand across the station's ORDER nodes and greedily
 * allocates the available stock to them (most-needed first), emitting a put-list of
 * {@link PutInstruction}s — one stock HU serving many destinations (the goods-to-person batch).
 * Confirming a put decrements the cycle's remaining stock and the destination's demand, completing
 * the destination when fully putted. Surplus demand stays OPEN for the next presentation
 * (short-pick handling). The logic is identical for ORDER_LOCATION and PUT_WALL stations; the only
 * difference is what an ORDER node represents physically.
 */
@Service
public class WorkCycleService {

    private final GtpStationRepository stations;
    private final StationNodeRepository nodes;
    private final DestinationDemandRepository demands;
    private final WorkCycleRepository cycles;
    private final PutInstructionRepository puts;
    private final TaskLineRepository taskLines;

    public WorkCycleService(GtpStationRepository stations,
                            StationNodeRepository nodes,
                            DestinationDemandRepository demands,
                            WorkCycleRepository cycles,
                            PutInstructionRepository puts,
                            TaskLineRepository taskLines) {
        this.stations = stations;
        this.nodes = nodes;
        this.demands = demands;
        this.cycles = cycles;
        this.puts = puts;
        this.taskLines = taskLines;
    }

    /**
     * Present a stock HU at the station and build its put-list. Open demand for the SKU across the
     * station's ORDER nodes is filled most-needed-first until either all demand is met or the
     * presented qty is exhausted.
     */
    @Transactional
    public WorkCycle present(UUID stationId, PresentStockRequest request) {
        GtpStation station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("station", stationId));

        requireSupported(station, OperatingMode.PICKING);
        UUID stockNodeId = resolveStockNode(stationId, request.stockNodeId());

        WorkCycle cycle = new WorkCycle();
        cycle.setStationId(stationId);
        cycle.setOperatingMode(OperatingMode.PICKING.name());
        cycle.setStockNodeId(stockNodeId);
        cycle.setStockHuId(request.stockHuId());
        cycle.setSkuId(request.skuId());
        cycle.setPresentedQty(request.qty());
        cycle.setRemainingQty(request.qty());
        cycles.save(cycle);

        BigDecimal available = request.qty();
        List<DestinationDemand> open = demands.findOpenForStationAndSku(stationId, request.skuId());
        for (DestinationDemand demand : open) {
            if (available.signum() <= 0) {
                break;
            }
            BigDecimal need = demand.remaining();
            BigDecimal put = need.min(available);
            if (put.signum() <= 0) {
                continue;
            }
            StationNode dest = nodes.findById(demand.getStationNodeId())
                    .orElseThrow(() -> new NotFoundException("station node", demand.getStationNodeId()));

            PutInstruction instruction = new PutInstruction();
            instruction.setWorkCycleId(cycle.getId());
            instruction.setDestinationNodeId(dest.getId());
            instruction.setDestinationDemandId(demand.getId());
            instruction.setOrderRef(demand.getOrderRef());
            instruction.setOrderLineId(demand.getOrderLineId());
            instruction.setOrderHuId(dest.getOrderHuId());
            instruction.setPutLightId(dest.getPutLightId());
            instruction.setQty(put);
            puts.save(instruction);

            available = available.subtract(put);
        }
        // remaining_qty reflects stock not yet reserved by put instructions; it is decremented on
        // confirmation. At present time everything assigned to a put is still in the HU.
        return cycle;
    }

    /**
     * Open/start a work cycle in a given operating mode and present its HU(s), generating the
     * mode-appropriate task lines (the put-list generalised). PICKING is delegated to
     * {@link #present} (demand matching); the other modes build {@link TaskLine}s from the request:
     *
     * <ul>
     *   <li>DECANTING — one DECANT_MOVE line per requested move (SKU + qty into a target compartment
     *       of {@code targetHuId}).</li>
     *   <li>STOCK_COUNT — one COUNT_ENTRY line per SKU to count (expected = system qty).</li>
     *   <li>QC — one QC_VERDICT line per SKU (or a single HU-level line).</li>
     *   <li>MAINTENANCE — one MAINTENANCE_CHECK line per requested HU/carrier.</li>
     * </ul>
     */
    @Transactional
    public WorkCycle startCycle(UUID stationId, StartCycleRequest request) {
        GtpStation station = stations.findById(stationId)
                .orElseThrow(() -> new NotFoundException("station", stationId));
        OperatingMode mode = OperatingMode.parse(request.operatingMode());
        requireSupported(station, mode);

        if (mode == OperatingMode.PICKING) {
            return present(stationId, new PresentStockRequest(
                    request.stockNodeId(), request.stockHuId(), request.skuId(), request.qty()));
        }

        UUID stockNodeId = resolveStockNode(stationId, request.stockNodeId());
        WorkCycle cycle = new WorkCycle();
        cycle.setStationId(stationId);
        cycle.setOperatingMode(mode.name());
        cycle.setStockNodeId(stockNodeId);
        cycle.setStockHuId(request.stockHuId());
        cycle.setTargetHuId(request.targetHuId());
        cycle.setSkuId(request.skuId());
        cycles.save(cycle);

        List<StartCycleRequest.LineSpec> specs =
                request.lines() == null ? List.of() : request.lines();
        switch (mode) {
            case DECANTING -> {
                if (request.targetHuId() == null) {
                    throw new IllegalArgumentException("DECANTING requires a target HU");
                }
                for (StartCycleRequest.LineSpec spec : specs) {
                    if (spec.skuId() == null || spec.qty() == null || spec.qty().signum() <= 0) {
                        throw new IllegalArgumentException(
                                "each decant move needs a skuId and a positive qty");
                    }
                    TaskLine line = newLine(cycle, "DECANT_MOVE");
                    line.setHuId(request.targetHuId());
                    line.setSkuId(spec.skuId());
                    line.setCompartment(spec.compartment());
                    line.setExpectedQty(spec.qty());
                    line.setPutLightId(spec.putLightId());
                    taskLines.save(line);
                }
            }
            case STOCK_COUNT -> {
                for (StartCycleRequest.LineSpec spec : specs) {
                    if (spec.skuId() == null) {
                        throw new IllegalArgumentException("each count entry needs a skuId");
                    }
                    TaskLine line = newLine(cycle, "COUNT_ENTRY");
                    line.setHuId(spec.huId() != null ? spec.huId() : request.stockHuId());
                    line.setSkuId(spec.skuId());
                    line.setExpectedQty(spec.qty()); // system/expected qty to count against
                    taskLines.save(line);
                }
            }
            case QC -> {
                for (StartCycleRequest.LineSpec spec : specs) {
                    TaskLine line = newLine(cycle, "QC_VERDICT");
                    line.setHuId(spec.huId() != null ? spec.huId() : request.stockHuId());
                    line.setSkuId(spec.skuId());
                    taskLines.save(line);
                }
            }
            case MAINTENANCE -> {
                for (StartCycleRequest.LineSpec spec : specs) {
                    TaskLine line = newLine(cycle, "MAINTENANCE_CHECK");
                    line.setHuId(spec.huId() != null ? spec.huId() : request.stockHuId());
                    taskLines.save(line);
                }
            }
            default -> throw new IllegalArgumentException("unsupported operating mode: " + mode);
        }
        return cycle;
    }

    /**
     * Submit the outcome of a non-PICKING task line and confirm it:
     *
     * <ul>
     *   <li>DECANT_MOVE — records the moved {@code actualQty}; on confirm the target HU's filled
     *       compartment SKUs are ready for slotting put-away (a seam — see
     *       {@link #decantedTargetReady}, not hard-wired).</li>
     *   <li>COUNT_ENTRY — records the counted {@code actualQty} and computes
     *       {@code variance = actual − expected}; a non-zero variance is a StockAdjusted intent
     *       (a seam to inventory — see {@link #stockCountAdjustment}, not hard-wired).</li>
     *   <li>QC_VERDICT — records {@code verdict} ∈ {PASS, FAIL, HOLD}.</li>
     *   <li>MAINTENANCE_CHECK — records {@code verdict} ∈ {OK, DEFECTIVE, REPAIR}.</li>
     * </ul>
     */
    @Transactional
    public TaskLine submitOutcome(UUID taskLineId, SubmitOutcomeRequest request) {
        TaskLine line = taskLines.findById(taskLineId)
                .orElseThrow(() -> new NotFoundException("task line", taskLineId));
        if (!"OPEN".equals(line.getStatus())) {
            throw new IllegalStateException("task line is not OPEN: " + line.getStatus());
        }
        switch (line.getLineType()) {
            case "DECANT_MOVE" -> {
                BigDecimal moved = request != null && request.actualQty() != null
                        ? request.actualQty() : line.getExpectedQty();
                if (moved == null || moved.signum() < 0) {
                    throw new IllegalArgumentException("decant move qty must be zero or positive");
                }
                line.setActualQty(moved);
            }
            case "COUNT_ENTRY" -> {
                if (request == null || request.actualQty() == null) {
                    throw new IllegalArgumentException("a count entry needs a counted qty");
                }
                if (request.actualQty().signum() < 0) {
                    throw new IllegalArgumentException("counted qty must be zero or positive");
                }
                line.setActualQty(request.actualQty());
                BigDecimal expected = line.getExpectedQty() == null
                        ? BigDecimal.ZERO : line.getExpectedQty();
                line.setVariance(request.actualQty().subtract(expected));
            }
            case "QC_VERDICT" -> line.setVerdict(requireVerdict(request, "PASS", "FAIL", "HOLD"));
            case "MAINTENANCE_CHECK" ->
                    line.setVerdict(requireVerdict(request, "OK", "DEFECTIVE", "REPAIR"));
            default -> throw new IllegalStateException("unknown task-line type: " + line.getLineType());
        }
        line.setStatus("CONFIRMED");

        WorkCycle cycle = cycles.findById(line.getWorkCycleId())
                .orElseThrow(() -> new NotFoundException("work cycle", line.getWorkCycleId()));
        if (taskLines.findByWorkCycleId(cycle.getId()).stream()
                .noneMatch(t -> "OPEN".equals(t.getStatus()))) {
            cycle.setStatus("COMPLETED");
        }
        return line;
    }

    /**
     * Seam (not hard-wired): the filled target HU of a confirmed DECANTING cycle plus the SKUs it now
     * holds — what a slotting put-away call would consume. Returned for callers/adapters to act on;
     * GTP does not invoke slotting itself.
     */
    @Transactional(readOnly = true)
    public DecantedTarget decantedTargetReady(UUID cycleId) {
        WorkCycle cycle = requireCycle(cycleId);
        List<UUID> skus = taskLines.findByWorkCycleId(cycleId).stream()
                .filter(t -> "DECANT_MOVE".equals(t.getLineType()) && "CONFIRMED".equals(t.getStatus()))
                .map(TaskLine::getSkuId)
                .distinct()
                .toList();
        return new DecantedTarget(cycle.getTargetHuId(), skus);
    }

    /**
     * Seam (not hard-wired): the non-zero count variances of a STOCK_COUNT cycle — the StockAdjusted
     * intents an inventory integration would post. GTP records them; it does not adjust inventory.
     */
    @Transactional(readOnly = true)
    public List<CountAdjustment> stockCountAdjustment(UUID cycleId) {
        requireCycle(cycleId);
        return taskLines.findByWorkCycleId(cycleId).stream()
                .filter(t -> "COUNT_ENTRY".equals(t.getLineType())
                        && t.getVariance() != null && t.getVariance().signum() != 0)
                .map(t -> new CountAdjustment(t.getHuId(), t.getSkuId(), t.getExpectedQty(),
                        t.getActualQty(), t.getVariance()))
                .toList();
    }

    /** The filled target HU of a decant cycle + its compartment SKUs (slotting put-away seam). */
    public record DecantedTarget(UUID targetHuId, List<UUID> skuIds) {
    }

    /** A counted variance (StockAdjusted intent seam). */
    public record CountAdjustment(UUID huId, UUID skuId, BigDecimal expectedQty,
                                  BigDecimal countedQty, BigDecimal variance) {
    }

    private TaskLine newLine(WorkCycle cycle, String lineType) {
        TaskLine line = new TaskLine();
        line.setWorkCycleId(cycle.getId());
        line.setLineType(lineType);
        return line;
    }

    private String requireVerdict(SubmitOutcomeRequest request, String... allowed) {
        String v = request == null ? null : request.verdict();
        if (v == null) {
            throw new IllegalArgumentException("a verdict is required (one of " + String.join(", ", allowed) + ")");
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw new IllegalArgumentException("verdict must be one of " + String.join(", ", allowed) + ": " + v);
    }

    private void requireSupported(GtpStation station, OperatingMode mode) {
        if (!station.supports(mode)) {
            throw new IllegalStateException(
                    "station " + station.getId() + " does not support operating mode " + mode
                            + " (supports " + station.getSupportedModes() + ")");
        }
    }

    /**
     * Confirm a put. With no qty (or the full lit qty) the instruction is CONFIRMED; a smaller qty
     * is a SHORT put. Decrements the cycle's remaining stock and the destination's putted qty, and
     * completes the destination demand when fully satisfied.
     */
    @Transactional
    public PutInstruction confirm(UUID putInstructionId, ConfirmPutRequest request) {
        PutInstruction instruction = puts.findById(putInstructionId)
                .orElseThrow(() -> new NotFoundException("put instruction", putInstructionId));
        if (!"OPEN".equals(instruction.getStatus())) {
            throw new IllegalStateException("put instruction is not OPEN: " + instruction.getStatus());
        }

        BigDecimal confirmed = request != null && request.qty() != null ? request.qty() : instruction.getQty();
        if (confirmed.signum() <= 0) {
            throw new IllegalArgumentException("confirmed qty must be positive");
        }
        if (confirmed.compareTo(instruction.getQty()) > 0) {
            throw new IllegalArgumentException("confirmed qty exceeds the lit quantity");
        }

        WorkCycle cycle = cycles.findById(instruction.getWorkCycleId())
                .orElseThrow(() -> new NotFoundException("work cycle", instruction.getWorkCycleId()));
        DestinationDemand demand = demands.findById(instruction.getDestinationDemandId())
                .orElseThrow(() -> new NotFoundException("destination demand",
                        instruction.getDestinationDemandId()));

        instruction.setConfirmedQty(confirmed);
        instruction.setStatus(confirmed.compareTo(instruction.getQty()) < 0 ? "SHORT" : "CONFIRMED");

        demand.setPuttedQty(demand.getPuttedQty().add(confirmed));
        if (demand.remaining().signum() <= 0) {
            demand.setStatus("COMPLETED");
        }

        cycle.setRemainingQty(cycle.getRemainingQty().subtract(confirmed));
        if (allResolved(cycle.getId())) {
            cycle.setStatus("COMPLETED");
        }
        return instruction;
    }

    /** Close a cycle (e.g. the stock HU is sent away); any OPEN puts are cancelled. */
    @Transactional
    public WorkCycle close(UUID cycleId) {
        WorkCycle cycle = cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundException("work cycle", cycleId));
        for (PutInstruction p : puts.findByWorkCycleId(cycleId)) {
            if ("OPEN".equals(p.getStatus())) {
                p.setStatus("CANCELLED");
            }
        }
        for (TaskLine t : taskLines.findByWorkCycleId(cycleId)) {
            if ("OPEN".equals(t.getStatus())) {
                t.setStatus("CANCELLED");
            }
        }
        if (!"COMPLETED".equals(cycle.getStatus())) {
            cycle.setStatus("CLOSED");
        }
        return cycle;
    }

    @Transactional(readOnly = true)
    public WorkCycle requireCycle(UUID cycleId) {
        return cycles.findById(cycleId)
                .orElseThrow(() -> new NotFoundException("work cycle", cycleId));
    }

    @Transactional(readOnly = true)
    public List<PutInstruction> putsOf(UUID cycleId) {
        return puts.findByWorkCycleId(cycleId);
    }

    @Transactional(readOnly = true)
    public List<TaskLine> taskLinesOf(UUID cycleId) {
        return taskLines.findByWorkCycleId(cycleId);
    }

    @Transactional(readOnly = true)
    public String modeOf(UUID stationId) {
        return stations.findById(stationId).map(GtpStation::getMode).orElse(null);
    }

    private boolean allResolved(UUID cycleId) {
        return puts.findByWorkCycleId(cycleId).stream().noneMatch(p -> "OPEN".equals(p.getStatus()));
    }

    private UUID resolveStockNode(UUID stationId, UUID requested) {
        List<StationNode> stockNodes = nodes.findByStationIdAndRole(stationId, "STOCK");
        if (stockNodes.isEmpty()) {
            throw new IllegalStateException("station has no STOCK node configured");
        }
        if (requested == null) {
            return stockNodes.get(0).getId();
        }
        return stockNodes.stream()
                .map(StationNode::getId)
                .filter(requested::equals)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "stockNodeId is not a STOCK node of this station: " + requested));
    }
}
