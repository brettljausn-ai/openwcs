package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.UUID;
import org.openwcs.slotting.api.PutawayDecision;
import org.openwcs.slotting.api.PutawayRequest;
import org.openwcs.slotting.client.FlowClient;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.client.InventoryClient.HandlingUnitView;
import org.openwcs.slotting.client.InventoryClient.StockBucket;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.domain.ReslotRecommendation;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.openwcs.slotting.repo.ReslotRecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Execution layer (epic 1): turns slotting PLANS into real dispatched physical moves by calling
 * flow-orchestrator ({@code POST /api/flow/moves}). Three seams:
 * <ol>
 *   <li><b>re-slot dispatch</b> — a RECOMMENDED {@link ReslotRecommendation} (hu/from/to/family
 *       already decided) becomes a move and flips to DISPATCHED;</li>
 *   <li><b>replenishment dispatch</b> — a PLANNED {@link ReplenishmentTask} resolves its source
 *       reserve location (best-effort), then moves stock to the pick face and flips to DISPATCHED;</li>
 *   <li><b>decant → put-away</b> — a GTP decant produced a storable HU: run the put-away scorer to
 *       choose a slot, then move the HU from the decant station to that slot.</li>
 * </ol>
 *
 * <p>Best-effort on the flow call: the move is dispatched <i>before</i> the plan status flips, so a
 * flow hiccup ({@link FlowClient.FlowDispatchException}) leaves the plan un-dispatched and surfaces
 * the error rather than corrupting state. Dispatched plans move off RECOMMENDED / PLANNED so they
 * are never dispatched twice, and carry the created {@code deviceTaskId}.
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private static final String RECOMMENDED = "RECOMMENDED";
    private static final String PLANNED = "PLANNED";
    private static final String DISPATCHED = "DISPATCHED";

    private final ReslotRecommendationRepository recommendations;
    private final ReplenishmentTaskRepository tasks;
    private final PickSlotRepository pickSlots;
    private final PutawayService putaway;
    private final InventoryClient inventory;
    private final FlowClient flow;

    public DispatchService(ReslotRecommendationRepository recommendations,
                           ReplenishmentTaskRepository tasks, PickSlotRepository pickSlots,
                           PutawayService putaway, InventoryClient inventory, FlowClient flow) {
        this.recommendations = recommendations;
        this.tasks = tasks;
        this.pickSlots = pickSlots;
        this.putaway = putaway;
        this.inventory = inventory;
        this.flow = flow;
    }

    /**
     * Dispatch a RECOMMENDED re-slot recommendation as a physical move. The recommendation already
     * carries hu / from / to; we look up the HU code for the move, call flow, then flip to
     * DISPATCHED and record the device-task id.
     *
     * @throws java.util.NoSuchElementException if the recommendation does not exist
     * @throws IllegalStateException            (409) if it is not RECOMMENDED
     */
    @Transactional
    public ReslotRecommendation dispatchReslot(UUID id) {
        ReslotRecommendation rec = recommendations.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("re-slot recommendation " + id + " not found"));
        if (!RECOMMENDED.equals(rec.getStatus())) {
            throw new IllegalStateException(
                    "re-slot recommendation " + id + " is " + rec.getStatus() + ", only RECOMMENDED can be dispatched");
        }
        String huCode = huCode(rec.getWarehouseId(), rec.getHuId());
        UUID deviceTaskId = flow.dispatchMove(rec.getWarehouseId(), rec.getHuId(), huCode,
                rec.getFromLocationId(), rec.getToLocationId(), "RESLOT",
                rec.getReason() == null ? "re-slot" : "re-slot: " + rec.getReason());
        rec.setStatus(DISPATCHED);
        rec.setDeviceTaskId(deviceTaskId);
        ReslotRecommendation saved = recommendations.save(rec);
        log.info("re-slot recommendation {} dispatched: hu {} {} -> {} (device task {})",
                id, rec.getHuId(), rec.getFromLocationId(), rec.getToLocationId(), deviceTaskId);
        return saved;
    }

    /**
     * Dispatch a PLANNED replenishment task as a physical move. Resolves a source reserve location
     * holding the SKU (best-effort, FEFO-ish: a non-pick-face stock bucket with on-hand, most stock
     * first); if none is resolvable the move still goes out with a null from-location and flow picks
     * the HU up wherever the registry says it is. The to-location is the pick face. On success the
     * task flips to DISPATCHED.
     *
     * @throws java.util.NoSuchElementException if the task does not exist
     * @throws IllegalStateException            (409) if it is not PLANNED
     */
    @Transactional
    public ReplenishmentTask dispatchReplenishment(UUID taskId) {
        ReplenishmentTask task = tasks.findById(taskId)
                .orElseThrow(() -> new java.util.NoSuchElementException("replenishment task " + taskId + " not found"));
        if (!PLANNED.equals(task.getStatus())) {
            throw new IllegalStateException(
                    "replenishment task " + taskId + " is " + task.getStatus() + ", only PLANNED can be dispatched");
        }
        Source source = resolveSource(task);
        UUID deviceTaskId = flow.dispatchMove(task.getWarehouseId(), source.huId(), huCode(task.getWarehouseId(), source.huId()),
                source.locationId(), task.getToLocationId(), "REPLENISHMENT",
                "replenishment " + task.getTriggerType() + " (" + task.getPriority() + ")");
        task.setFromLocationId(source.locationId());
        task.setStatus(DISPATCHED);
        task.setDeviceTaskId(deviceTaskId);
        ReplenishmentTask saved = tasks.save(task);
        log.info("replenishment task {} dispatched: sku {} from {} -> pick face {} (device task {})",
                taskId, task.getSkuId(), source.locationId(), task.getToLocationId(), deviceTaskId);
        return saved;
    }

    /**
     * Decant → put-away seam: a GTP decant produced a storable HU. Run the existing put-away scorer
     * to choose a destination slot, then dispatch a move from the decant/station location to that
     * slot. Returns both the put-away decision and the created device-task id.
     */
    @Transactional
    public DecantResult dispatchDecantPutaway(UUID warehouseId, UUID huId, UUID skuId, BigDecimal qty,
                                              UUID fromLocationId) {
        PutawayDecision decision = putaway.assign(
                new PutawayRequest(warehouseId, huId, skuId, null, null, qty, null, null, false, java.util.List.of()));
        UUID from = fromLocationId != null ? fromLocationId : currentLocation(warehouseId, huId);
        UUID deviceTaskId = flow.dispatchMove(warehouseId, huId, huCode(warehouseId, huId),
                from, decision.locationId(), "PUTAWAY", "decant put-away");
        log.info("decant put-away dispatched: hu {} sku {} from {} -> slot {} (device task {})",
                huId, skuId, from, decision.locationId(), deviceTaskId);
        return new DecantResult(decision, deviceTaskId);
    }

    /** The put-away decision plus the dispatched move's device-task id. */
    public record DecantResult(PutawayDecision decision, UUID deviceTaskId) {
    }

    /** Resolved replenishment source: a reserve location holding the SKU, plus the holding HU. */
    private record Source(UUID locationId, UUID huId) {
    }

    /**
     * Best-effort source resolution for a replenishment task: scan the SKU's stock buckets for an
     * AVAILABLE bucket that is NOT the destination pick face and is not another fixed pick face,
     * preferring the most stock (FEFO ordering would slot in here once batch expiry is exposed).
     * Returns a null location/hu when nothing is resolvable — the move then dispatches with a blank
     * from-location and flow picks the HU up from its registered position.
     */
    private Source resolveSource(ReplenishmentTask task) {
        java.util.Set<UUID> pickFaces = pickSlots.findByWarehouseIdAndSkuId(task.getWarehouseId(), task.getSkuId())
                .stream().map(PickSlot::getLocationId).collect(java.util.stream.Collectors.toSet());

        StockBucket best = inventory.stockForSku(task.getWarehouseId(), task.getSkuId()).stream()
                .filter(b -> b.locationId() != null)
                .filter(b -> !b.locationId().equals(task.getToLocationId()))
                .filter(b -> !pickFaces.contains(b.locationId()))
                .filter(b -> b.status() == null || "AVAILABLE".equals(b.status()))
                .filter(b -> b.qty() == null || b.qty().signum() > 0)
                .max(Comparator.comparing(b -> b.qty() == null ? BigDecimal.ZERO : b.qty()))
                .orElse(null);
        if (best == null) {
            log.warn("replenishment task {}: no reserve source resolvable for sku {} (dispatching with blank"
                    + " from-location; flow picks up the HU at its registered position)",
                    task.getId(), task.getSkuId());
            return new Source(null, null);
        }
        return new Source(best.locationId(), best.huId());
    }

    /** The HU's code from the registry (null if unknown / unreachable) — for the move payload. */
    private String huCode(UUID warehouseId, UUID huId) {
        if (huId == null) {
            return null;
        }
        try {
            return inventory.handlingUnits(warehouseId).stream()
                    .filter(h -> huId.equals(h.huId()))
                    .map(HandlingUnitView::code)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The HU's current physical location from the registry (null if unknown / in transit). */
    private UUID currentLocation(UUID warehouseId, UUID huId) {
        if (huId == null) {
            return null;
        }
        try {
            return inventory.handlingUnits(warehouseId).stream()
                    .filter(h -> huId.equals(h.huId()))
                    .map(HandlingUnitView::locationId)
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
