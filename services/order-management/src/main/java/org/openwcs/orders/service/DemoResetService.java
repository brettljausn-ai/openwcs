package org.openwcs.orders.service;

import java.util.UUID;
import org.openwcs.orders.api.DemoClearResult;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode for the order-management service (build.md §4.8). When demo mode is turned off
 * the operational state is fully reset: every order for the warehouse (any type — INBOUND /
 * OUTBOUND / COUNT / ADJUSTMENT — all held in {@code outbound_order}) is deleted, cascading to
 * its lines and line transactions, and the order outbox is drained. Infrastructure and
 * master-data references are untouched.
 */
@Service
public class DemoResetService {

    private final OutboundOrderRepository orders;
    private final OrderOutboxRepository outbox;

    public DemoResetService(OutboundOrderRepository orders, OrderOutboxRepository outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        // Bulk statements only — order books can be large; the DB cascades lines, line
        // transactions and their outbox rows (ON DELETE CASCADE), so one DELETE per table.
        int ordersRemoved = orders.deleteBulkByWarehouseId(warehouseId);

        long outboxRemoved = outbox.count();
        outbox.deleteAllInBatch();

        return new DemoClearResult(ordersRemoved, outboxRemoved);
    }
}
