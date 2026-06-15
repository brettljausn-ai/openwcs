package org.openwcs.orders.service;

import java.util.UUID;
import org.openwcs.orders.api.DemoClearResult;
import org.openwcs.orders.repo.OrderOutboxRepository;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.openwcs.orders.repo.RouteDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);

    private final OutboundOrderRepository orders;
    private final OrderOutboxRepository outbox;
    private final RouteDispatchRepository routeDispatches;

    public DemoResetService(OutboundOrderRepository orders, OrderOutboxRepository outbox,
                            RouteDispatchRepository routeDispatches) {
        this.orders = orders;
        this.outbox = outbox;
        this.routeDispatches = routeDispatches;
    }

    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        // Bulk statements only — order books can be large; the DB cascades lines, line
        // transactions and their outbox rows (ON DELETE CASCADE), so one DELETE per table.
        int ordersRemoved = orders.deleteBulkByWarehouseId(warehouseId);
        // The dispatch waves are not children of orders (keyed by route + cut-off), so clear them
        // explicitly, otherwise stale waves would linger on the dispatch board after a reset.
        int wavesRemoved = routeDispatches.deleteBulkByWarehouseId(warehouseId);

        long outboxRemoved = outbox.count();
        outbox.deleteAllInBatch();

        log.info("demo clear for warehouse {}: {} orders deleted (lines and transactions cascade), "
                        + "{} dispatch waves cleared, {} outbox rows drained",
                warehouseId, ordersRemoved, wavesRemoved, outboxRemoved);
        return new DemoClearResult(ordersRemoved, outboxRemoved);
    }
}
