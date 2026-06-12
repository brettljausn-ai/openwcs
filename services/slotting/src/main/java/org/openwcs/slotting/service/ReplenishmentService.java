package org.openwcs.slotting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.client.InventoryClient;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.domain.ReplenishmentTask;
import org.openwcs.slotting.repo.PickSlotRepository;
import org.openwcs.slotting.repo.ReplenishmentTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps fixed pick faces stocked (ADR 0003):
 * <ul>
 *   <li><b>below-min (reactive)</b> — when on-hand drops to/below {@code minQty}, raise a task to
 *       refill to {@code maxQty}; EMERGENCY if the face is empty, else SCHEDULED;</li>
 *   <li><b>top-off (opportunistic)</b> — during off-peak windows, refill every face that is below
 *       {@code maxQty} up to max (OPPORTUNISTIC).</li>
 * </ul>
 *
 * <p>Tasks are deduplicated against open tasks for the same face. Source-location (reserve / block)
 * selection by FEFO is a fast-follow with physical move execution; {@code fromLocationId} is left
 * null here.
 */
@Service
public class ReplenishmentService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentService.class);

    private static final String PLANNED = "PLANNED";

    private final PickSlotRepository pickSlots;
    private final ReplenishmentTaskRepository tasks;
    private final InventoryClient inventory;

    public ReplenishmentService(PickSlotRepository pickSlots, ReplenishmentTaskRepository tasks,
                                InventoryClient inventory) {
        this.pickSlots = pickSlots;
        this.tasks = tasks;
        this.inventory = inventory;
    }

    /** Reactive below-min pass for a warehouse. */
    @Transactional
    public List<ReplenishmentTask> planBelowMin(UUID warehouseId) {
        return run(warehouseId, false);
    }

    /** Opportunistic top-off-to-max pass for a warehouse (off-peak). */
    @Transactional
    public List<ReplenishmentTask> topOff(UUID warehouseId) {
        return run(warehouseId, true);
    }

    private List<ReplenishmentTask> run(UUID warehouseId, boolean topOff) {
        List<ReplenishmentTask> created = new ArrayList<>();
        for (PickSlot slot : pickSlots.findByWarehouseIdAndStatus(warehouseId, "ACTIVE")) {
            ReplenishmentTask task = consider(slot, topOff);
            if (task != null) {
                created.add(task);
            }
        }
        return created;
    }

    private ReplenishmentTask consider(PickSlot slot, boolean topOff) {
        BigDecimal onHand = inventory.onHandAtLocation(slot.getWarehouseId(), slot.getSkuId(), slot.getLocationId());
        if (onHand == null) {
            onHand = BigDecimal.ZERO;
        }

        boolean belowMin = onHand.compareTo(slot.getMinQty()) <= 0;
        if (!topOff && !belowMin) {
            return null; // reactive pass only fires below min
        }
        if (topOff && onHand.compareTo(slot.getMaxQty()) >= 0) {
            return null; // nothing to top off
        }

        BigDecimal qty = slot.getMaxQty().subtract(onHand);
        if (qty.signum() <= 0) {
            return null;
        }
        // Dedup against an already-open task for this face.
        if (!tasks.findByWarehouseIdAndToLocationIdAndStatus(slot.getWarehouseId(), slot.getLocationId(), PLANNED).isEmpty()) {
            if (belowMin) {
                log.warn("replenishment for pick face {} skipped: open task already planned"
                                + " (sku {} on-hand {} at/below min {}); face stays low until that task completes",
                        slot.getLocationId(), slot.getSkuId(), onHand, slot.getMinQty());
            } else {
                log.debug("top-off for pick face {} skipped: open replenishment task already planned",
                        slot.getLocationId());
            }
            return null;
        }

        String priority;
        String trigger;
        if (belowMin) {
            trigger = "BELOW_MIN";
            priority = onHand.signum() <= 0 ? "EMERGENCY" : "SCHEDULED";
        } else {
            trigger = "TOP_OFF";
            priority = "OPPORTUNISTIC";
        }

        ReplenishmentTask task = new ReplenishmentTask();
        task.setWarehouseId(slot.getWarehouseId());
        task.setSkuId(slot.getSkuId());
        task.setUomId(slot.getUomId());
        task.setToLocationId(slot.getLocationId());
        task.setQty(qty);
        task.setPriority(priority);
        task.setTriggerType(trigger);
        task.setStatus(PLANNED);
        ReplenishmentTask saved = tasks.save(task);
        log.info("replenishment task {} for pick face {}: sku {} on-hand {} (min {}, max {}),"
                        + " refill {} to max ({}, priority {})",
                saved.getId(), slot.getLocationId(), slot.getSkuId(), onHand,
                slot.getMinQty(), slot.getMaxQty(), qty, trigger, priority);
        return saved;
    }
}
