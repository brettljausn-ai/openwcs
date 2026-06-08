package org.openwcs.gtp.service;

import java.math.BigDecimal;
import java.util.UUID;
import org.openwcs.gtp.client.TxLogClient;
import org.openwcs.gtp.domain.MaintenanceOrder;
import org.openwcs.gtp.domain.StationQueueEntry;
import org.openwcs.gtp.repo.MaintenanceOrderRepository;
import org.openwcs.gtp.repo.StationQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator exceptions raised at a goods-to-person station when a tote leaves the station for a reason
 * other than "all work done":
 *
 * <ul>
 *   <li>dirty-tote: the tote is flagged for cleaning. It is pulled out of circulation into a
 *       {@link MaintenanceOrder} and the queue entry is completed WITHOUT a store-back (it goes to
 *       maintenance, not back to stock).</li>
 *   <li>broken-product: some quantity on the tote is damaged. A negative {@code StockAdjusted} (reason
 *       DAMAGED) is posted to the txlog so inventory writes the loss off; the tote stays in the queue
 *       so the operator keeps working it.</li>
 * </ul>
 */
@Service
public class StationExceptionService {

    private static final Logger log = LoggerFactory.getLogger(StationExceptionService.class);

    private final StationQueueEntryRepository queue;
    private final MaintenanceOrderRepository maintenance;
    private final StationQueueService queueService;
    private final TxLogClient txlog;

    public StationExceptionService(StationQueueEntryRepository queue, MaintenanceOrderRepository maintenance,
                                   StationQueueService queueService, TxLogClient txlog) {
        this.queue = queue;
        this.maintenance = maintenance;
        this.queueService = queueService;
        this.txlog = txlog;
    }

    /**
     * Dirty-tote: open a CLEANING maintenance order from the queue entry and complete the entry without
     * storing it back. The tote is routed to maintenance instead of returning to inventory.
     */
    @Transactional
    public MaintenanceOrder markDirty(UUID stationId, UUID queueEntryId) {
        StationQueueEntry e = entry(queueEntryId);
        MaintenanceOrder order = new MaintenanceOrder();
        order.setWarehouseId(e.getWarehouseId());
        order.setHuId(e.getHuId());
        order.setHuCode(e.getHuCode());
        order.setStationId(stationId);
        order.setSkuId(e.getSkuId());
        order.setSkuCode(e.getSkuCode());
        order.setReason(MaintenanceOrder.Reason.CLEANING.name());
        order.setStatus(MaintenanceOrder.Status.OPEN.name());
        MaintenanceOrder saved = maintenance.save(order);

        queueService.completeWithoutStoreBack(queueEntryId);
        log.info("tote {} flagged dirty at station {}; maintenance order {} opened (CLEANING)",
                e.getHuCode(), stationId, saved.getId());
        return saved;
    }

    /**
     * Broken-product: write off {@code qty} units of the tote's SKU with a negative DAMAGED stock
     * adjustment. The tote stays in the queue (the operator keeps working it). Returns the (positive)
     * adjusted quantity.
     */
    @Transactional
    public BigDecimal markBroken(UUID queueEntryId, BigDecimal qty, String actor) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("qty must be greater than 0.");
        }
        StationQueueEntry e = entry(queueEntryId);
        txlog.postStockAdjusted(new TxLogClient.StockAdjustment(
                e.getWarehouseId(), e.getSkuId(), null, e.getLocationId(),
                qty.negate(), null, "DAMAGED", actor == null || actor.isBlank() ? "system" : actor));
        log.info("tote {} (sku {}) reported broken at station; wrote off {} (DAMAGED)",
                e.getHuCode(), e.getSkuCode(), qty);
        return qty;
    }

    private StationQueueEntry entry(UUID queueEntryId) {
        return queue.findById(queueEntryId)
                .orElseThrow(() -> new StationQueueService.QueueRejectedException("Queue entry not found."));
    }
}
