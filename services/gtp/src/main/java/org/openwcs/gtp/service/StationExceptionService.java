package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.gtp.client.FlowInductionClient;
import org.openwcs.gtp.client.TxLogClient;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.openwcs.gtp.repo.MaintenanceOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator exceptions raised at a goods-to-person station when a tote leaves the station for a reason
 * other than "all work done". Since ADR-0007 Phase 3c-1 these act on the flow-owned induction entry
 * id (the inbound queue lives in flow, not gtp):
 *
 * <ul>
 *   <li>dirty-tote: the tote is flagged for cleaning. It is pulled out of circulation into a
 *       {@link MaintenanceOrder} and the flow induction entry is marked DONE WITHOUT a store-back (it
 *       goes to maintenance, not back to stock).</li>
 *   <li>broken-product: some quantity on the tote is damaged. A negative {@code StockAdjusted} (reason
 *       DAMAGED) is posted to the txlog so inventory writes the loss off; the tote stays in the queue
 *       so the operator keeps working it.</li>
 * </ul>
 */
@Service
public class StationExceptionService {

    private static final Logger log = LoggerFactory.getLogger(StationExceptionService.class);

    private final MaintenanceOrderRepository maintenance;
    private final StationQueueService queueService;
    private final FlowInductionClient induction;
    private final TxLogClient txlog;

    public StationExceptionService(MaintenanceOrderRepository maintenance,
                                   StationQueueService queueService, FlowInductionClient induction,
                                   TxLogClient txlog) {
        this.maintenance = maintenance;
        this.queueService = queueService;
        this.induction = induction;
        this.txlog = txlog;
    }

    /**
     * Dirty-tote: open a CLEANING maintenance order from the flow induction entry and mark the entry
     * DONE without storing it back. The tote is routed to maintenance instead of returning to inventory.
     */
    @Transactional
    public MaintenanceOrder markDirty(UUID stationId, UUID inductionEntryId) {
        FlowInductionClient.InductionEntry e = entry(inductionEntryId);
        MaintenanceOrder order = new MaintenanceOrder();
        order.setWarehouseId(e.warehouseId());
        order.setHuId(e.huId());
        order.setHuCode(e.huCode());
        order.setStationId(stationId);
        order.setSkuId(e.skuId());
        order.setSkuCode(e.skuCode());
        order.setReason(MaintenanceOrder.Reason.CLEANING.name());
        order.setStatus(MaintenanceOrder.Status.OPEN.name());
        MaintenanceOrder saved = maintenance.save(order);

        queueService.completeInductionWithoutStoreBack(inductionEntryId);
        log.info("tote {} flagged dirty at station {}; maintenance order {} opened (CLEANING)",
                e.huCode(), stationId, saved.getId());
        return saved;
    }

    /**
     * Broken-product: write off {@code qty} units of the tote's SKU with a negative DAMAGED stock
     * adjustment. The tote stays in the queue (the operator keeps working it). Returns the (positive)
     * adjusted quantity.
     */
    @Transactional
    public BigDecimal markBroken(UUID inductionEntryId, BigDecimal qty, String actor) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("qty must be greater than 0.");
        }
        FlowInductionClient.InductionEntry e = entry(inductionEntryId);
        txlog.postStockAdjusted(new TxLogClient.StockAdjustment(
                e.warehouseId(), e.skuId(), null, e.locationId(),
                qty.negate(), null, "DAMAGED", actor == null || actor.isBlank() ? "system" : actor));
        log.info("tote {} (sku {}) reported broken at station; wrote off {} (DAMAGED)",
                e.huCode(), e.skuCode(), qty);
        return qty;
    }

    private FlowInductionClient.InductionEntry entry(UUID inductionEntryId) {
        FlowInductionClient.InductionEntry e = induction.getEntry(inductionEntryId);
        if (e == null) {
            throw new StationQueueService.QueueRejectedException("Induction entry not found.");
        }
        return e;
    }
}
